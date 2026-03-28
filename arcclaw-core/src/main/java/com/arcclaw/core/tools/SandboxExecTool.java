package com.arcclaw.core.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.sandbox.ProcessIsolationExecutor;
import com.arcclaw.core.sandbox.SandboxCodeSafetyScanner;
import com.arcclaw.core.sandbox.SandboxResult;
import com.arcclaw.core.sandbox.ToolExecutionException;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.ToolOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        "The code was NOT run. Inform the user gracefully that the execution environment is currently offline.";

    private static final Set<String> ALLOWED_LANGUAGES =
            Set.of("python", "python3", "bash", "node", "java", "go");

    private static final Pattern JAVA_CLASS_PATTERN =
            Pattern.compile("(?:public\\s+)?class\\s+(\\w+)", Pattern.MULTILINE);

    static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    @Inject ProcessIsolationExecutor isolationExecutor;
    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = ".data/workspaces")
    String workspaceRootDir;

    @ConfigProperty(name = "arcclaw.sandbox.python-executable")
    Optional<String> configuredPythonExe;

    @Override public String name()             { return ToolCapability.SANDBOX_EXEC.toolName(); }
    @Override public String description()      { return ToolCapability.SANDBOX_EXEC.description(); }
    @Override public ToolOperation requiredOperation() { return ToolOperation.EXECUTE_SHELL; }
    @Override public boolean requiresSandbox() { return false; }
    public ToolCapability capability()         { return ToolCapability.SANDBOX_EXEC; }

    // Colons are illegal in Windows path components (drive-letter separator).
    // Inner session IDs contain colons: {outer}:d:{role}:{uuid}
    // Sanitize for filesystem use only — sessionId itself is never modified.
    private static final Pattern FS_ILLEGAL_CHARS = Pattern.compile("[:<>\"\\\\|?*]");

    private static String toFsName(String sessionId) {
        return FS_ILLEGAL_CHARS.matcher(sessionId).replaceAll("_");
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        try {
            // Defensive unwrap: LLMs occasionally echo JSON Schema {"properties":{...}}
            if (params.size() == 1 && params.containsKey("properties")
                    && params.get("properties") instanceof Map<?, ?> inner) {
                @SuppressWarnings("unchecked")
                Map<String, Object> unwrapped = (Map<String, Object>) inner;
                params = unwrapped;
            }

            String code = (String) params.get("code");
            String lang = (String) params.get("language");

            if (code == null) {
                return ToolResponse.failure(1, "Parameter 'code' is required.");
            }
            if (lang == null) {
                return ToolResponse.failure(1,
                        "Parameter 'language' is required. Allowed: " + ALLOWED_LANGUAGES +
                        ". Example: {\"code\":\"print('hi')\",\"language\":\"python\"}");
            }
            lang = lang.toLowerCase();

            if (!ALLOWED_LANGUAGES.contains(lang)) {
                return ToolResponse.failure(1,
                        "Unsupported language: " + lang + ". Allowed: " + ALLOWED_LANGUAGES);
            }

            String violation = SandboxCodeSafetyScanner.findViolation(lang, code);
            if (violation != null) {
                log.warnf("[SandboxExecTool] Blocked dangerous pattern in session %s: %s",
                        WorkspaceContext.requireSessionId(), violation);
                return ToolResponse.failure(1, "🚫 Execution blocked: " + violation);
            }

            String sessionId = WorkspaceContext.requireSessionId();
            Path workspaceRoot = Path.of(workspaceRootDir, toFsName(sessionId));

            Map<String, String> requiredSecrets = Map.of();
            Object secretsObj = params.get("secrets");
            if (secretsObj instanceof Map<?, ?> secretsMap) {
                requiredSecrets = objectMapper.convertValue(secretsMap,
                        objectMapper.getTypeFactory().constructMapType(
                                Map.class, String.class, String.class));
            }

            return switch (lang) {
                case "java" -> executeJava(sessionId, workspaceRoot, code, requiredSecrets);
                case "go"   -> executeGo(sessionId, workspaceRoot, code, requiredSecrets);
                default     -> executeInterpreted(sessionId, workspaceRoot, lang, code,
                                                  params, requiredSecrets);
            };

        } catch (ToolExecutionException e) {
            return ToolResponse.failure(e.exitCode(),
                    nonBlank("Execution failed: " + e.getMessage()));
        } catch (TimeoutException | InterruptedException e) {
            return ToolResponse.failure(1, "Sandbox execution timed out.");
        } catch (SecurityException e) {
            return ToolResponse.failure(1,
                    nonBlank("Sandbox security violation: " + e.getMessage()));
        } catch (BudgetExceededException e) {
            throw e;
        } catch (Exception e) {
            log.errorf(e, "SandboxExecTool unexpected error for session %s",
                    WorkspaceContext.requireSessionId());
            return ToolResponse.failure(1,
                    nonBlank("Sandbox error: " + e.getMessage()));
        }
    }

    // ── Interpreted: Python, Node, Bash ──────────────────────────────────────

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
        if (result.isInfraFailure()) {
            log.errorf("[SandboxExecTool] Infra failure (interpreted/%s) session=%s: %s",
                    lang, sessionId, result.reason());
            return ToolResponse.infraFailure(SANDBOX_OFFLINE_MSG);
        }
        return ToolResponse.success(truncate(result.output()));
    }

    // ── Java: write ClassName.java → javac → java -cp ────────────────────────

    // Container-side mount point — must match what ProcessIsolationExecutor mounts workspaceRoot as.
    // The Dockerfile sets WORKDIR /workspace; ProcessIsolationExecutor mounts workspaceRoot there.
    private static final String CONTAINER_WORKSPACE = "/workspace";

    private ToolResponse executeJava(String sessionId, Path workspaceRoot,
        String code, Map<String, String> secrets) throws Exception {

        Files.createDirectories(workspaceRoot);
        String className = extractJavaClassName(code);
        Path javaFile = workspaceRoot.resolve(className + ".java");
        Files.writeString(javaFile, code, StandardCharsets.UTF_8);

        // Use CONTAINER_WORKSPACE path for javac argument
        List<String> compileCmd = List.of("javac",
                CONTAINER_WORKSPACE + "/" + className + ".java");
        try {
            SandboxResult compileResult = isolationExecutor.executeSandboxed(
                    sessionId, workspaceRoot, compileCmd, secrets);
            if (compileResult.isInfraFailure()) {
                log.errorf("[SandboxExecTool] Infra failure (java/compile) session=%s: %s",
                        sessionId, compileResult.reason());
                return ToolResponse.infraFailure(SANDBOX_OFFLINE_MSG);
            }
        } catch (ToolExecutionException e) {
            return ToolResponse.failure(e.exitCode(),
                    nonBlank("Java compilation failed:\n" + e.getMessage()));
        }

        // Use CONTAINER_WORKSPACE for -cp classpath argument
        String javaExe = resolveJavaExecutable();
        List<String> runCmd = List.of(javaExe, "-cp", CONTAINER_WORKSPACE, className);
        SandboxResult runResult = isolationExecutor.executeSandboxed(
                sessionId, workspaceRoot, runCmd, secrets);
        if (runResult.isInfraFailure()) {
            log.errorf("[SandboxExecTool] Infra failure (java/run) session=%s: %s",
                    sessionId, runResult.reason());
            return ToolResponse.infraFailure(SANDBOX_OFFLINE_MSG);
        }
        return ToolResponse.success(truncate(runResult.output()));
    }

    // ── Go: write main.go → go run ────────────────────────────────────────────

    private ToolResponse executeGo(String sessionId, Path workspaceRoot,
        String code, Map<String, String> secrets) throws Exception {

        Files.createDirectories(workspaceRoot);
        Path goFile = workspaceRoot.resolve("main.go");
        Files.writeString(goFile, code, StandardCharsets.UTF_8);

        // Use CONTAINER_WORKSPACE path — workspaceRoot is the HOST path,
        // which does not exist inside the Podman container.
        List<String> runCmd = List.of("go", "run", CONTAINER_WORKSPACE + "/main.go");
        SandboxResult result = isolationExecutor.executeSandboxed(
                sessionId, workspaceRoot, runCmd, secrets);
        if (result.isInfraFailure()) {
            log.errorf("[SandboxExecTool] Infra failure (go) session=%s: %s",
                    sessionId, result.reason());
            return ToolResponse.infraFailure(SANDBOX_OFFLINE_MSG);
        }
        return ToolResponse.success(truncate(result.output()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
        return configuredPythonExe
                .filter(exe -> !exe.isBlank())
                .orElse(IS_WINDOWS ? "python" : "python3");
    }

    /**
     * Resolves java executable. On Windows, uses the running JVM's java.exe
     * to guarantee we use the same JDK that is running ArcClaw, bypassing
     * any PATH ambiguity (e.g., a different JRE on PATH).
     * Package-private for unit tests.
     */
    String resolveJavaExecutable() {
        if (IS_WINDOWS) {
            String javaHome = System.getProperty("java.home");
            if (javaHome != null) {
                Path javaExe = Path.of(javaHome, "bin", "java.exe");
                if (Files.exists(javaExe)) return javaExe.toString();
            }
        }
        return "java";
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

    /**
     * Ensures error messages passed to ToolResponse.failure() are never null/blank.
     * A blank failure message would also cause "text cannot be null or blank" when
     * LangChain4J wraps the tool result into a ToolExecutionResultMessage.
     */
    private String nonBlank(String message) {
        if (message == null || message.isBlank()) return "Sandbox execution failed with no details.";
        return message;
    }
}