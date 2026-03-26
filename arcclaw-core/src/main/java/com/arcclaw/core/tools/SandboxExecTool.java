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

    // Interpreted: inline via -c / -e flags — no temp file needed.
    // Compiled:    write to file, compile, then run.
    private static final Set<String> ALLOWED_LANGUAGES =
            Set.of("python", "python3", "bash", "node", "java", "go");

    // Matches "public class Foo" or "class Foo" to derive the .java filename
    private static final Pattern JAVA_CLASS_PATTERN =
            Pattern.compile("(?:public\\s+)?class\\s+(\\w+)", Pattern.MULTILINE);

    static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    @Inject
    ProcessIsolationExecutor isolationExecutor;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = ".data/workspaces")
    String workspaceRootDir;

    @ConfigProperty(name = "arcclaw.sandbox.python-executable")
    Optional<String> configuredPythonExe;

    @Override
    public String name() { return ToolCapability.SANDBOX_EXEC.toolName(); }

    @Override
    public String description() { return ToolCapability.SANDBOX_EXEC.description(); }

    @Override
    public ToolOperation requiredOperation() { return ToolOperation.EXECUTE_SHELL; }

    @Override
    public boolean requiresSandbox() { return false; }

    public ToolCapability capability() { return ToolCapability.SANDBOX_EXEC; }

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

            // Safety scan — last line of defence in DIRECT mode
            String violation = SandboxCodeSafetyScanner.findViolation(lang, code);
            if (violation != null) {
                log.warnf("[SandboxExecTool] Blocked dangerous pattern in session %s: %s",
                        WorkspaceContext.requireSessionId(), violation);
                return ToolResponse.failure(1, "🚫 Execution blocked: " + violation);
            }

            String sessionId = WorkspaceContext.requireSessionId();
            Path workspaceRoot = Path.of(workspaceRootDir, sessionId);

            Map<String, String> requiredSecrets = Map.of();
            Object secretsObj = params.get("secrets");
            if (secretsObj instanceof Map<?, ?> secretsMap) {
                requiredSecrets = objectMapper.convertValue(secretsMap,
                        objectMapper.getTypeFactory().constructMapType(
                                Map.class, String.class, String.class));
            }

            // Dispatch: compiled languages use file-based execution
            return switch (lang) {
                case "java" -> executeJava(sessionId, workspaceRoot, code, requiredSecrets);
                case "go"   -> executeGo(sessionId, workspaceRoot, code, requiredSecrets);
                default     -> executeInterpreted(sessionId, workspaceRoot, lang, code,
                                                  params, requiredSecrets);
            };

        } catch (ToolExecutionException e) {
            return ToolResponse.failure(e.exitCode(), "Execution failed: " + e.getMessage());
        } catch (TimeoutException | InterruptedException e) {
            return ToolResponse.failure(1, "Sandbox execution timed out.");
        } catch (SecurityException e) {
            return ToolResponse.failure(1, "Sandbox security violation: " + e.getMessage());
        } catch (BudgetExceededException e) {
            throw e;
        } catch (Exception e) {
            log.errorf(e, "SandboxExecTool unexpected error for session %s",
                    WorkspaceContext.requireSessionId());
            return ToolResponse.failure(1, "Sandbox error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Interpreted languages: Python, Node, Bash — inline, no temp file
    // -------------------------------------------------------------------------

    private ToolResponse executeInterpreted(String sessionId, Path workspaceRoot,
            String lang, String code, Map<String, Object> params,
            Map<String, String> secrets) throws Exception {

        List<String> commandList = new ArrayList<>(buildCommand(lang, code));

        Object argsObj = params.get("args");
        if (argsObj instanceof List<?> extraArgs) {
            for (Object arg : extraArgs) commandList.add(arg.toString());
        }

        String output = isolationExecutor.executeSandboxed(
                sessionId, workspaceRoot, commandList, secrets);
        return ToolResponse.success(truncate(output));
    }

    // -------------------------------------------------------------------------
    // Java: write ClassName.java → javac → java -cp workspaceRoot ClassName
    // -------------------------------------------------------------------------

    private ToolResponse executeJava(String sessionId, Path workspaceRoot,
            String code, Map<String, String> secrets) throws Exception {

        Files.createDirectories(workspaceRoot);
        String className = extractJavaClassName(code);
        Path javaFile = workspaceRoot.resolve(className + ".java");
        Files.writeString(javaFile, code, StandardCharsets.UTF_8);

        // Step 1: compile
        List<String> compileCmd = List.of("javac", javaFile.toString());
        try {
            isolationExecutor.executeSandboxed(sessionId, workspaceRoot, compileCmd, secrets);
        } catch (ToolExecutionException e) {
            // Compilation error output IS useful — return it as structured failure
            return ToolResponse.failure(e.exitCode(),
                    "Java compilation failed:\n" + e.getMessage());
        }

        // Step 2: run — use current JVM location on Windows to avoid PATH issues
        String javaExe = resolveJavaExecutable();
        List<String> runCmd = List.of(javaExe, "-cp", workspaceRoot.toString(), className);
        String output = isolationExecutor.executeSandboxed(
                sessionId, workspaceRoot, runCmd, secrets);
        return ToolResponse.success(truncate(output));
    }

    // -------------------------------------------------------------------------
    // Go: write main.go → go run
    // -------------------------------------------------------------------------

    private ToolResponse executeGo(String sessionId, Path workspaceRoot,
            String code, Map<String, String> secrets) throws Exception {

        Files.createDirectories(workspaceRoot);
        Path goFile = workspaceRoot.resolve("main.go");
        Files.writeString(goFile, code, StandardCharsets.UTF_8);

        List<String> runCmd = List.of("go", "run", goFile.toString());
        String output = isolationExecutor.executeSandboxed(
                sessionId, workspaceRoot, runCmd, secrets);
        return ToolResponse.success(truncate(output));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Package-private for unit tests. */
    List<String> buildCommand(String lang, String code) {
        return switch (lang) {
            case "python", "python3" -> List.of(resolvePythonExecutable(), "-c", code);
            case "bash"              -> List.of("bash", "-c", code);
            case "node"              -> List.of("node", "-e", code);
            default -> throw new IllegalStateException(
                    "buildCommand() called for file-based lang: " + lang);
        };
    }

    /** Package-private for unit tests. */
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
            // ProcessHandle gives us the exact java.exe running this JVM
            String javaHome = System.getProperty("java.home");
            if (javaHome != null) {
                Path javaExe = Path.of(javaHome, "bin", "java.exe");
                if (Files.exists(javaExe)) {
                    return javaExe.toString();
                }
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
        // First pass: prefer public class
        Matcher m = Pattern.compile("public\\s+class\\s+(\\w+)", Pattern.MULTILINE).matcher(code);
        if (m.find()) return m.group(1);
        // Second pass: any class
        m = JAVA_CLASS_PATTERN.matcher(code);
        if (m.find()) return m.group(1);
        return "Main";
    }

    private String truncate(String output) {
        if (output == null) return "";
        return output.length() > MAX_OUTPUT_CHARS
                ? output.substring(0, MAX_OUTPUT_CHARS) + TRUNCATION_SUFFIX
                : output;
    }
}