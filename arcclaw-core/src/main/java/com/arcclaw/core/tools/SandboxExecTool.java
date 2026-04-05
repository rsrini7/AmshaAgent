package com.arcclaw.core.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.sandbox.ProcessIsolationExecutor;
import com.arcclaw.core.sandbox.SandboxCodeSafetyScanner;
import com.arcclaw.core.sandbox.SandboxResult;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.ToolOperation;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SandboxExecTool implements AgentTool {

    private static final Logger log = Logger.getLogger(SandboxExecTool.class);
    private static final int MAX_OUTPUT_CHARS = 8192;
    private static final String TRUNCATION_SUFFIX = "[output truncated at 8192 chars]";

    // ── Sentinel returned when executeSandboxed() gives null/empty output.
    // LangChain4J will throw "text cannot be null or blank" if ToolExecutionResultMessage
    // receives an empty string — this sentinel prevents that.
    private static final String NO_OUTPUT_SENTINEL = "[execution produced no output]";

    // ── Single source of truth for the infra-failure user message.
    // Never copy-paste this string — always reference this constant.
    // Logs contain the raw reason; the LLM/user only ever sees this sanitized message.
    private static final String SANDBOX_OFFLINE_MSG =
        "SANDBOX_OFFLINE: System Error - Podman sandbox execution failed."+
        "The code was NOT run. Inform the user gracefully that the execution environment is currently offline."+
        "or set .env file ARCCLAW_SANDBOX_ALLOW_INSECURE_FALLBACK=true to allow insecure fallback";

    private static final Pattern JAVA_CLASS_PATTERN =
            Pattern.compile("(?:public\\s+)?class\\s+(\\w+)", Pattern.MULTILINE);

    static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    @Inject ProcessIsolationExecutor isolationExecutor;

    // Colons are illegal in Windows path components (drive-letter separator).
    // Inner session IDs contain colons: {outer}:d:{role}:{uuid}
    // Sanitize for filesystem use only — sessionId itself is never modified.
    private static final Pattern FS_ILLEGAL_CHARS = Pattern.compile("[:<>\"\\\\|?*]");

    private static String toFsName(String sessionId) {
        return FS_ILLEGAL_CHARS.matcher(sessionId).replaceAll("_");
    }

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = ".data/workspaces")
    String workspaceRootDir;

    @ConfigProperty(name = "arcclaw.sandbox.python-executable")
    Optional<String> configuredPythonExe;

    @ConfigProperty(name = "arcclaw.sandbox.go-executable")
    Optional<String> configuredGoExe;

    @ConfigProperty(name = "arcclaw.sandbox.java-executable")
    Optional<String> configuredJavaExe;

    @Override public String name()             { return ToolCapability.SANDBOX_EXEC.toolName(); }
    @Override public String description()      { return ToolCapability.SANDBOX_EXEC.description(); }
    @Override public ToolOperation requiredOperation() { return ToolOperation.EXECUTE_SHELL; }
    @Override public boolean requiresSandbox() { return true; }
    public ToolCapability capability()         { return ToolCapability.SANDBOX_EXEC; }

    @Override
    public List<String> sandboxCommand(Map<String, Object> params) {
        String code = (String) params.get("code");
        String lang = (String) params.get("language");
        if (code == null) throw new IllegalArgumentException("Parameter 'code' is required.");
        if (lang == null) throw new IllegalArgumentException("Parameter 'language' is required.");
        lang = lang.toLowerCase();

        // Security scanner check - prevent dangerous patterns before generating the command
        String violation = SandboxCodeSafetyScanner.findViolation(lang, code);
        if (violation != null) {
            log.warnf("[SandboxExecTool] Blocked dangerous pattern in session %s: %s",
                    WorkspaceContext.requireSessionId(), violation);
            throw new IllegalArgumentException("🚫 Execution blocked: " + violation);
        }

        return switch (lang) {
            case "java" -> {
                String className = extractJavaClassName(code);
                yield List.of("java", "--source", "21", CONTAINER_WORKSPACE + "/" + className + ".java");
            }
            case "go"   -> List.of("go", "run", CONTAINER_WORKSPACE + "/main.go");
            default     -> buildCommand(lang, code);
        };
    }

    @Override
    public void prepareWorkspace(Path workspaceRoot, Map<String, Object> params) throws IOException {
        String lang = (String) params.get("language");
        String code = (String) params.get("code");
        if (lang == null || code == null) return;
        lang = lang.toLowerCase();

        if ("java".equals(lang)) {
            String className = extractJavaClassName(code);
            Files.writeString(workspaceRoot.resolve(className + ".java"), code);
        } else if ("go".equals(lang)) {
            Files.writeString(workspaceRoot.resolve("main.go"), code);
        }
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        // This is now only called when requiresSandbox() is false or as a manual fallback.
        // The ToolRegistry normally uses sandboxCommand() when requiresSandbox() is true.
        try {
            String code = (String) params.get("code");
            String lang = (String) params.get("language");
            if (code == null || lang == null) return ToolResponse.failure(1, "code and language required");
            lang = lang.toLowerCase();

            // Safety scanner check for fallback execution path
            String violation = SandboxCodeSafetyScanner.findViolation(lang, code);
            if (violation != null) {
                return ToolResponse.failure(1, "🚫 Execution blocked: " + violation);
            }

            String sessionId = WorkspaceContext.requireSessionId();
            Path workspaceRoot = Path.of(workspaceRootDir, toFsName(sessionId));

            // Secrets are intentionally empty on the direct-execution fallback path.
            // The sandboxCommand() → PodmanContainerExecutor path injects secrets
            // securely via SandboxSecretResolver + tmpfs transport.
            // DirectExecutor has no secure secret injection mechanism — tools that
            // require secrets (e.g. GitHub token) should only run via Podman.
            if (params.containsKey("secrets")) {
                log.warnf("[SandboxExecTool] 'secrets' parameter ignored on direct host fallback — " +
                          "Podman unavailable. session=%s", WorkspaceContext.requireSessionId());
            }
            Map<String, String> requiredSecrets = Map.of();

            return switch (lang) {
                case "java" -> executeJava(sessionId, workspaceRoot, code, requiredSecrets);
                case "go"   -> executeGo(sessionId, workspaceRoot, code, requiredSecrets);
                default     -> executeInterpreted(sessionId, workspaceRoot, lang, code,
                                                  params, requiredSecrets);
            };
        } catch (Exception e) {
            String msg = e.getMessage();
            return ToolResponse.failure(1, msg != null && !msg.isBlank() 
                ? msg : "Sandbox execution failed with no details.");
        }
    }

    // Interpreted: Python, Node, Bash

    private ToolResponse executeInterpreted(String sessionId, Path workspaceRoot,
            String lang, String code, Map<String, Object> params,
            Map<String, String> secrets) throws Exception {

        List<String> commandList = new ArrayList<>(buildCommand(lang, code));

        Object argsObj = params.get("args");
        if (argsObj instanceof List<?> extraArgs) {
            for (Object arg : extraArgs) commandList.add(arg.toString());
        }

        SandboxResult result = isolationExecutor.executeSandboxed(
                sessionId, workspaceRoot, commandList, secrets);
        if (result == null || result.isInfraFailure()) {
            String reason = result != null ? result.reason() : "Sandbox executor returned null";
            log.errorf("[SandboxExecTool] Infra failure (interpreted/%s) session=%s: %s",
                    lang, sessionId, reason);
            return ToolResponse.infraFailure(SANDBOX_OFFLINE_MSG);
        }
        return ToolResponse.success(truncate(result.output()));
    }

    // Java: write ClassName.java → javac → java -cp

    // Container-side mount point — must match what ProcessIsolationExecutor mounts workspaceRoot as.
    // The Dockerfile sets WORKDIR /workspace; ProcessIsolationExecutor mounts workspaceRoot there.
    private static final String CONTAINER_WORKSPACE = "/workspace";

    private ToolResponse executeJava(String sessionId, Path workspaceRoot,
        String code, Map<String, String> secrets) throws Exception {

        Files.createDirectories(workspaceRoot);
        String className = extractJavaClassName(code);
        Path javaFile = workspaceRoot.resolve(className + ".java");
        Files.writeString(javaFile, code, StandardCharsets.UTF_8);

        // JDK 11+ / JEP 330: Run single-file source code directly.
        // This avoids creating .class files and bypasses traditional compilation disk-write errors.
        String javaExe = resolveJavaExecutable();
        List<String> runCmd = List.of(javaExe,
                "--source", "21",
                CONTAINER_WORKSPACE + "/" + className + ".java");

        SandboxResult result = isolationExecutor.executeSandboxed(
                sessionId, workspaceRoot, runCmd, secrets);
        if (result == null || result.isInfraFailure()) {
            String reason = result != null ? result.reason() : "Sandbox executor returned null";
            log.errorf("[SandboxExecTool] Infra failure (java) session=%s: %s",
                    sessionId, reason);
            return ToolResponse.infraFailure(SANDBOX_OFFLINE_MSG);
        }
        return ToolResponse.success(truncate(result.output()));
    }

    // Go: write main.go → go run

    private ToolResponse executeGo(String sessionId, Path workspaceRoot, String code, Map<String, String> secrets) throws Exception {
        Files.createDirectories(workspaceRoot);
        Path goFile = workspaceRoot.resolve("main.go");
        Files.writeString(goFile, code, StandardCharsets.UTF_8);

        // Use CONTAINER_WORKSPACE path — workspaceRoot is the HOST path,
        // which does not exist inside the Podman container.
        String goExe = resolveGoExecutable();
        List<String> runCmd = List.of(goExe, "run", CONTAINER_WORKSPACE + "/main.go");
        try {
            SandboxResult result = isolationExecutor.executeSandboxed(
                    sessionId, workspaceRoot, runCmd, secrets);
            if (result == null || result.isInfraFailure()) {
                String reason = result != null ? result.reason() : "Sandbox executor returned null";
                log.errorf("[SandboxExecTool] Infra failure (go) session=%s: %s",
                        sessionId, reason);
                return ToolResponse.infraFailure(SANDBOX_OFFLINE_MSG);
            }
            return ToolResponse.success(truncate(result.output()));
        } catch (IOException e) {
            if (e.getMessage().contains("No such file or directory")) {
                return ToolResponse.failure(1,
                    "Go runtime not found on this system. " +
                    "Install Go or rephrase the task to use Python/Node instead."
                );
            }
            throw e;
        }
    }

    // Helpers

    List<String> buildCommand(String lang, String code) {
        return switch (lang) {
            case "python", "python3" -> List.of(resolvePythonExecutable(), "-c", code);
            case "bash"              -> List.of("bash", "-c", code);
            case "node"              -> List.of("node", "-e", code);
            default -> throw new IllegalStateException(
                    "buildCommand() called for file-based lang: " + lang);
        };
    }

    String resolvePythonExecutable() {
        return resolveExecutable(IS_WINDOWS ? "python" : "python3", configuredPythonExe);
    }

    String resolveJavaExecutable() {
        // Special case: On Windows, use the running JVM's java.exe if available
        if (IS_WINDOWS && configuredJavaExe.isEmpty()) {
            String javaHome = System.getProperty("java.home");
            if (javaHome != null) {
                Path javaExe = Path.of(javaHome, "bin", "java.exe");
                if (Files.exists(javaExe)) return javaExe.toString();
            }
        }
        return resolveExecutable("java", configuredJavaExe);
    }

    String resolveGoExecutable() {
        return resolveExecutable("go", configuredGoExe);
    }

    /**
     * Generic OS-aware executable resolver.
     * 1. Returns configured value if non-blank.
     * 2. Checks common host directories based on OS.
     * 3. Falls back to original command name (relying on system PATH).
     */
    private String resolveExecutable(String command, Optional<String> config) {
        if (config.isPresent() && !config.get().isBlank()) {
            return config.get();
        }

        // We return the bare command and let the environment's $PATH handle it.
        // If we are running in a Podman container, absolute host paths (like /opt/homebrew/bin)
        // are invalid. If we are running locally, the system PATH will handle it.
        return command;
    }

    /**
     * Extracts the class name from Java source to derive the correct filename.
     * Prefers the public class name; falls back to first class found; defaults to "Main".
     * Package-private for unit tests.
     */
    String extractJavaClassName(String code) {
        Matcher m = Pattern.compile("public\\s+class\\s+(\\w+)", Pattern.MULTILINE).matcher(code);
        if (m.find()) return m.group(1);
        m = JAVA_CLASS_PATTERN.matcher(code);
        if (m.find()) return m.group(1);
        return "Main";
    }

    /**
     * Truncates output and replaces null/empty with a sentinel string.
     * Prevents blank ToolExecutionResultMessage which causes LangChain4J to throw
     * "text cannot be null or blank" on the next LLM call.
     */
    private String truncate(String output) {
        if (output == null || output.isBlank()) {
            return NO_OUTPUT_SENTINEL;
        }
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        // Codepoint-safe truncation to prevent splitting UTF-16 surrogate pairs
        int endIndex = output.offsetByCodePoints(
                0, Math.min(output.codePointCount(0, output.length()), MAX_OUTPUT_CHARS));
        return output.substring(0, endIndex) + TRUNCATION_SUFFIX;
    }

}
