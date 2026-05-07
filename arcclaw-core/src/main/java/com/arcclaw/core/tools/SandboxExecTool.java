package com.arcclaw.core.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.sandbox.ProcessIsolationExecutor;
import com.arcclaw.core.sandbox.SandboxCodeSafetyScanner;
import com.arcclaw.core.sandbox.SandboxResult;
import com.arcclaw.core.security.ToolOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * SandboxExecTool - executes user-submitted code inside an isolated Podman container.
 */
@ApplicationScoped
@ToolMetadata(
    capabilityId = "sandbox_exec",
    description = "Execute code in an isolated sandbox",
    defaultRoles = {"coder", "debugger"}
)
public class SandboxExecTool implements AgentTool {

    private static final Logger log = Logger.getLogger(SandboxExecTool.class);
    
    @ConfigProperty(name = "arcclaw.sandbox.max-output-chars", defaultValue = "8192")
    int maxOutputChars;
    
    private static final String TRUNCATION_SUFFIX = "[output truncated at max output chars limit]";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Container-side mount point for the workspace directory.
    // ProcessIsolationExecutor mounts workspaceRoot here read-only.
    // Must stay in sync with the -v flag in PodmanContainerExecutor.
    private static final String CONTAINER_WORKSPACE = "/workspace";

    // ── Sentinel returned when executeSandboxed() gives null/empty output.
    // LangChain4J throws "text cannot be null or blank" if ToolExecutionResultMessage
    // receives an empty string - this sentinel prevents that crash.
    private static final String NO_OUTPUT_SENTINEL = "[execution produced no output]";

    // ── Single source of truth for the infra-failure user message.
    // Never copy-paste this string - always reference this constant.
    // Logs contain the raw reason; the LLM/user only ever sees this sanitized message.
    private static final String SANDBOX_OFFLINE_MSG =
        "SANDBOX_OFFLINE: System Error - Podman sandbox execution failed. " +
        "The code was NOT run. Inform the user gracefully that the execution " +
        "environment is currently offline, or set " +
        "ARCCLAW_SANDBOX_ALLOW_INSECURE_FALLBACK=true to allow insecure fallback.";

    private static final Pattern PUBLIC_TYPE_PATTERN =
            Pattern.compile("(?:^|\\n)\\s*public\\s+(?:class|record|@?interface|enum)\\s+(\\w+)", Pattern.MULTILINE);

    private static final Pattern ANY_TYPE_PATTERN =
            Pattern.compile("(?:public\\s+)?(?:class|record|@?interface|enum)\\s+(\\w+)", Pattern.MULTILINE);

    static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    // Colons are illegal in Windows path components (drive-letter separator).
    // Inner session IDs contain colons (the ':delegate:' sentinel).
    // Sanitize for filesystem use only - sessionId itself is never modified.
    private static final Pattern FS_ILLEGAL_CHARS = Pattern.compile("[:<>\"\\\\|?*]");

    @Inject ProcessIsolationExecutor isolationExecutor;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    @ConfigProperty(name = "arcclaw.sandbox.python-executable")
    Optional<String> configuredPythonExe;

    @ConfigProperty(name = "arcclaw.sandbox.go-executable")
    Optional<String> configuredGoExe;

    @ConfigProperty(name = "arcclaw.sandbox.java-executable")
    Optional<String> configuredJavaExe;

    @Override
    public ToolResponse healthCheck() {
        if (isolationExecutor == null) {
            return ToolResponse.failure(503, "Sandbox isolation executor is not initialized.");
        }
        return ToolResponse.success("Sandbox execution environment is initialized.");
    }

    @Override public String name()             { return ToolCapability.SANDBOX_EXEC.toolName(); }
    @Override public String description()      { return ToolCapability.SANDBOX_EXEC.description(); }
    @Override public ToolOperation requiredOperation() { return ToolOperation.EXECUTE_SHELL; }
    @Override public boolean requiresSandbox() { return true; }
    public ToolCapability capability()         { return ToolCapability.SANDBOX_EXEC; }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("code", "The source code to execute")
                .addStringProperty("language", "The programming language (python, java, go, node, bash)")
                .required("code", "language")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // sandboxCommand() - returns the command that runs INSIDE the Podman container.
    //
    // Java path: prepareWorkspace() already compiled .java → .class on the host.
    //            The container just runs:  java -cp /workspace ClassName
    //            This only needs the JRE - no compiler required inside the image.
    //            See class-level Javadoc for why we chose this over java --source.
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public List<String> sandboxCommand(ToolExecutionContext ctx, Map<String, Object> params) {
        ensureUnwrapped(params);
        String code = (String) params.get("code");
        String lang = (String) params.get("language");
        if (code == null) throw new IllegalArgumentException("Parameter 'code' is required.");
        if (lang == null) throw new IllegalArgumentException("Parameter 'language' is required.");

        lang = lang.toLowerCase();

        String violation = SandboxCodeSafetyScanner.findViolation(lang, code);
        if (violation != null) {
            log.warnf("[SandboxExecTool] Blocked dangerous pattern in session %s: %s",
                    ctx.sessionId(), violation);
            throw new IllegalArgumentException("🚫 Execution blocked: " + violation);
        }

        return switch (lang) {
            case "java" -> {
                // .class files were written to workspaceRoot by prepareWorkspace().
                // Container mounts that dir as /workspace (read-only).
                // java -cp runs the compiled bytecode - JRE-only, no javac needed.
                String className = extractJavaClassName(code);
                yield List.of("java", "-cp", CONTAINER_WORKSPACE, className);
            }
            case "python", "python3" -> List.of("python3", CONTAINER_WORKSPACE + "/script.py");
            case "bash"              -> List.of("bash",    CONTAINER_WORKSPACE + "/script.sh");
            case "go" -> List.of("go", "run", CONTAINER_WORKSPACE + "/main.go");
            case "node" -> List.of("node", CONTAINER_WORKSPACE + "/script.js");
            default   -> buildCommand(lang, code);
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // prepareWorkspace() - runs on the HOST before the container is launched.
    //
    // Java: write .java file, then compile it to .class using the host JDK's
    //       javac (resolved from java.home - always present since Quarkus needs JDK).
    //       If compilation fails, throws IOException with the full javac error output
    //       so ToolRegistry surfaces it as a proper tool failure to the LLM.
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void prepareWorkspace(Path workspaceRoot, Map<String, Object> params) throws IOException {
        ensureUnwrapped(params);
        String lang = (String) params.get("language");
        String code = (String) params.get("code");
        if (lang == null || code == null) return;
        lang = lang.toLowerCase();

        if ("java".equals(lang)) {
            if (!ANY_TYPE_PATTERN.matcher(code).find()) {
                throw new IllegalArgumentException("Java execution failed: Code must contain a class declaration. Bare methods are not supported.");
            }
            Files.createDirectories(workspaceRoot);
            String className = extractJavaClassName(code);
            Path javaFile = workspaceRoot.resolve(className + ".java");
            Files.writeString(javaFile, code, StandardCharsets.UTF_8);
            // Compile on host - .class lands in same workspaceRoot dir.
            // Container mounts workspaceRoot as /workspace (read-only),
            // so java -cp /workspace finds the .class file at runtime.
            compileJavaOnHost(javaFile, workspaceRoot);

        } else if ("python".equals(lang) || "python3".equals(lang)) {
            // Write to file - avoids shell quote-stripping of f-strings, 
            // double quotes, and special chars when passed via -c to Podman.
            Files.createDirectories(workspaceRoot);
            Files.writeString(workspaceRoot.resolve("script.py"), code, StandardCharsets.UTF_8);

        } else if ("go".equals(lang)) {
            Files.createDirectories(workspaceRoot);
            Files.writeString(workspaceRoot.resolve("main.go"), code, StandardCharsets.UTF_8);
        } else if ("node".equals(lang)) {
            Files.createDirectories(workspaceRoot);
            Files.writeString(workspaceRoot.resolve("script.js"), code, StandardCharsets.UTF_8);
        } else if ("bash".equals(lang)) {
            Files.createDirectories(workspaceRoot);
            Files.writeString(workspaceRoot.resolve("script.sh"), code, StandardCharsets.UTF_8);
        } 
    }

    /**
     * Compiles a .java source file using the host JVM's javac binary.
     * Outputs .class files into outputDir (the session workspace).
     *
     * @throws IOException with the full javac error output if compilation fails.
     *                     ToolRegistry catches this and returns it as ToolResponse.failure()
     *                     so the LLM can fix the code rather than guessing what went wrong.
     */
    private void compileJavaOnHost(Path javaFile, Path outputDir) throws IOException {
        Path javacExe = resolveHostJavac();
        if (javacExe == null) {
            throw new IOException("Java compilation failed: 'javac' compiler not found on the host system. " +
                                  "Please ensure a full JDK is installed and 'javac' is on the PATH.");
        }

        ProcessBuilder pb = new ProcessBuilder(
                javacExe.toString(),
                "-source", "21",
                "-target", "21",
                "-d", outputDir.toString(),
                javaFile.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true); // merge stderr → stdout for full compiler output
        pb.directory(outputDir.toFile());

        Process process = pb.start();
        String compilerOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("javac was interrupted: " + e.getMessage());
        }

        if (exitCode != 0) {
            // Surface the real compiler error - LLM should fix the code, not hallucinate output.
            throw new IOException("Java compilation failed (exit " + exitCode + "):\n" + compilerOutput);
        }
        log.debugf("[SandboxExecTool] javac compiled %s → %s (exit 0)",
                javaFile.getFileName(), outputDir);
    }

    /**
     * Finds javac from the running JVM's java.home, or falls back to 'javac' on PATH.
     */
    private Path resolveHostJavac() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path javac = Path.of(javaHome, "bin", IS_WINDOWS ? "javac.exe" : "javac");
            if (Files.exists(javac)) return javac;
        }
        
        // Fallback for GraalVM native image mode where java.home is synthetic
        // or any case where JDK is on PATH but not in java.home
        return isCommandAvailable("javac") ? Path.of("javac") : null;
    }

    private boolean isCommandAvailable(String cmd) {
        try {
            Process process = new ProcessBuilder(IS_WINDOWS ? "where" : "which", cmd).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String postProcessOutput(String rawOutput, Map<String, Object> params) {
        return truncate(rawOutput);
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String sessionId = ctx.sessionId();

        try {
            String code = (String) params.get("code");
            String lang = (String) params.get("language");
            if (lang == null) return ToolResponse.failure(1, "Parameter 'language' is required.");
            if (code == null) return ToolResponse.failure(1, "Parameter 'code' is required.");
            lang = lang.toLowerCase();

            String violation = SandboxCodeSafetyScanner.findViolation(lang, code);
            if (violation != null) return ToolResponse.failure(1, "🚫 Execution blocked: " + violation);

            if ("java".equals(lang) && !ANY_TYPE_PATTERN.matcher(code).find()) {
                return ToolResponse.failure(1, "Java execution failed: Code must contain a class declaration. Bare methods are not supported.");
            }

            // Secrets are intentionally empty on the direct-execution fallback path.
            // The sandboxCommand() → PodmanContainerExecutor path injects secrets
            // securely via SandboxSecretResolver + tmpfs transport.
            // DirectExecutor has no secure secret injection mechanism.
            if (params.containsKey("secrets")) {
                log.warnf("[SandboxExecTool] 'secrets' parameter ignored on direct host fallback - " +
                          "Podman unavailable. session=%s", sessionId);
            }
            Map<String, String> requiredSecrets = Map.of();

            Path workspaceRoot = Path.of(workspaceRootDir, toFsName(sessionId));

            return switch (lang) {
                case "java" -> executeJavaFallback(sessionId, workspaceRoot, code, requiredSecrets);
                case "go"   -> executeGo(sessionId, workspaceRoot, code, requiredSecrets);
                default     -> executeInterpreted(sessionId, workspaceRoot, lang, code, params, requiredSecrets);
            };
        } catch (BudgetExceededException e) {
            throw e;
        } catch (TimeoutException e) {
            log.errorf("[SandboxExecTool] Execution timed out: %s", e.getMessage());
            return ToolResponse.infraFailure(SANDBOX_OFFLINE_MSG);
        } catch (Exception e) {
            String msg = e.getMessage();
            return ToolResponse.failure(1, msg != null && !msg.isBlank()
                ? msg : "Sandbox execution failed with no details.");
        }
    }

    private ToolResponse executeJavaFallback(String sessionId, Path workspaceRoot,
            String code, Map<String, String> secrets) throws Exception {

        Files.createDirectories(workspaceRoot);
        String className = extractJavaClassName(code);
        Path javaFile = workspaceRoot.resolve(className + ".java");
        Files.writeString(javaFile, code, StandardCharsets.UTF_8);

        try {
            compileJavaOnHost(javaFile, workspaceRoot);
        } catch (IOException compileError) {
            // Compilation failed - return the javac error so the LLM can fix the code.
            return ToolResponse.failure(1, compileError.getMessage());
        }

        String javaExe = resolveJavaExecutable();
        List<String> runCmd = List.of(javaExe, "-cp", workspaceRoot.toString(), className);

        SandboxResult result = isolationExecutor.executeSandboxed(sessionId, workspaceRoot, runCmd, secrets);
        if (result == null || result.isInfraFailure()) {
            String reason = result != null ? result.reason() : "Sandbox executor returned null";
            log.errorf("[SandboxExecTool] Infra failure (java-fallback) sessionId=%s: %s", sessionId, reason);
            return ToolResponse.infraFailure(SANDBOX_OFFLINE_MSG);
        }
        if (!result.success()) {
            return ToolResponse.failure(1, result.reason());
        }
        return ToolResponse.success(postProcessOutput(result.output(), Map.of()));
    }

    private ToolResponse executeInterpreted(String sessionId, Path workspaceRoot,
            String lang, String code, Map<String, Object> params,
            Map<String, String> secrets) throws Exception {

        List<String> commandList = new ArrayList<>(buildCommand(lang, code));
        Object argsObj = params.get("args");
        if (argsObj instanceof List<?> extraArgs) {
            for (Object arg : extraArgs) commandList.add(arg.toString());
        }

        SandboxResult result = isolationExecutor.executeSandboxed(sessionId, workspaceRoot, commandList, secrets);
        if (result == null || result.isInfraFailure()) {
            String reason = result != null ? result.reason() : "Sandbox executor returned null";
            log.errorf("[SandboxExecTool] Infra failure (interpreted/%s) session=%s: %s", lang, sessionId, reason);
            return ToolResponse.infraFailure(SANDBOX_OFFLINE_MSG);
        }
        if (!result.success()) {
            return ToolResponse.failure(1, result.reason());
        }
        return ToolResponse.success(postProcessOutput(result.output(), params));
    }

    private ToolResponse executeGo(String sessionId, Path workspaceRoot,
            String code, Map<String, String> secrets) throws Exception {
        Files.createDirectories(workspaceRoot);
        Files.writeString(workspaceRoot.resolve("main.go"), code, StandardCharsets.UTF_8);

        String goExe = resolveGoExecutable();
        List<String> runCmd = List.of(goExe, "run", CONTAINER_WORKSPACE + "/main.go");
        try {
            SandboxResult result = isolationExecutor.executeSandboxed(sessionId, workspaceRoot, runCmd, secrets);
            if (result == null || result.isInfraFailure()) {
                String reason = result != null ? result.reason() : "Sandbox executor returned null";
                log.errorf("[SandboxExecTool] Infra failure (go) session=%s: %s", sessionId, reason);
                return ToolResponse.infraFailure(SANDBOX_OFFLINE_MSG);
            }
            if (!result.success()) {
                return ToolResponse.failure(1, result.reason());
            }
            return ToolResponse.success(postProcessOutput(result.output(), Map.of()));
        } catch (IOException e) {
            if (e.getMessage().contains("No such file or directory")) {
                return ToolResponse.failure(1,
                    "Go runtime not found on this system. " +
                    "Install Go or rephrase the task to use Python/Node instead.");
            }
            throw e;
        }
    }

    List<String> buildCommand(String lang, String code) {
        return switch (lang) {
            // Python, node, and bash are now file-based via prepareWorkspace + sandboxCommand.
            // -c / -e is kept ONLY for the interpreted host-fallback path (execute() → executeInterpreted())
            // where ProcessBuilder performs a direct OS exec() - no shell intermediary, quotes preserved.
            case "python", "python3" -> List.of(resolvePythonExecutable(), "-c", code);
            case "bash"              -> List.of("bash", "-c", code);
            case "node"              -> List.of("node", "-e", code);
            default -> throw new IllegalArgumentException(
                    "Unsupported language '" + lang + "'. Only Java, Python, Go, Node, and Bash are supported.");
        };
    }

    String resolvePythonExecutable() {
        return resolveExecutable(IS_WINDOWS ? "python" : "python3", configuredPythonExe);
    }

    String resolveJavaExecutable() {
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

    private String resolveExecutable(String command, Optional<String> config) {
        if (config.isPresent() && !config.get().isBlank()) return config.get();
        return command;
    }

    private static String toFsName(String sessionId) {
        return FS_ILLEGAL_CHARS.matcher(sessionId).replaceAll("_");
    }

    String extractJavaClassName(String code) {
        Matcher m = PUBLIC_TYPE_PATTERN.matcher(code);
        if (m.find()) return m.group(1);
        m = ANY_TYPE_PATTERN.matcher(code);
        if (m.find()) return m.group(1);
        return "Main";
    }

    /**
     * Truncates output and replaces null/empty with a sentinel.
     * Prevents blank ToolExecutionResultMessage which causes LangChain4J to throw
     * "text cannot be null or blank" on the next LLM call.
     */
    private String truncate(String output) {
        if (output == null || output.isBlank()) return NO_OUTPUT_SENTINEL;
        if (output.length() <= maxOutputChars) return output;
        int endIndex = output.offsetByCodePoints(
                0, Math.min(output.codePointCount(0, output.length()), maxOutputChars));
        return output.substring(0, endIndex) + TRUNCATION_SUFFIX;
    }

    private void ensureUnwrapped(Map<String, Object> params) {
        if (params == null) return;
        Object codeObj = params.get("code");
        if (!(codeObj instanceof String code)) return;

        if (code.trim().startsWith("{") && code.contains("\"code\"") && code.contains("\"language\"")) {
            try {
                JsonNode node = MAPPER.readTree(code);
                if (node.has("code")) {
                    params.put("code", node.get("code").asText());
                }
                if (node.has("language")) {
                    params.put("language", node.get("language").asText());
                }
            } catch (Exception e) {
                log.debugf("Non-fatal (unwrapping): %s", ExceptionUtils.getRootCauseMessage(e));
                // ignore and proceed with raw code if parsing fails
            }
        }
    }
}
