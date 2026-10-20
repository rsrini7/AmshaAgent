package com.amsha.tools.builtin;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.config.CoreSpiResolutionValidator;
import com.amsha.core.governance.BudgetExceededException;
import com.amsha.core.sandbox.SandboxExecutionPort;
import com.amsha.core.sandbox.SandboxFailureMessages;
import com.amsha.core.sandbox.SandboxResult;
import com.amsha.core.tools.CodeLanguageDetector;
import com.amsha.core.tools.LanguageIds;
import com.amsha.core.tools.OutputTruncator;
import com.amsha.sandbox.SandboxCodeSafetyScanner;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolMetadata;
import com.amsha.tool.api.ToolNames;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * SandboxExecTool - executes user-submitted code inside an isolated Podman container.
 */
@ApplicationScoped
@Unremovable
@ToolMetadata(
        capabilityId = ToolNames.SANDBOX_EXEC,
        description = "Execute code in an isolated sandbox",
        defaultRoles = {AgentRoleEnum.CODER_ID, AgentRoleEnum.DEBUGGER_ID})
@SuppressWarnings("java:S1192")
public class SandboxExecTool implements AgentTool {

    private static final Logger log = Logger.getLogger(SandboxExecTool.class);

    @ConfigProperty(name = "amsha.sandbox.max-output-chars", defaultValue = "8192")
    int maxOutputChars = 8192;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Container-side mount point for the workspace directory.
    // ProcessIsolationExecutor mounts workspaceRoot here read-only.
    // Must stay in sync with the -v flag in PodmanContainerExecutor.
    private static final String CONTAINER_WORKSPACE = "/workspace";

    // ── Single source of truth for the infra-failure user message.
    private static final String SANDBOX_OFFLINE_MESSAGE = SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE;

    @Inject
    BeanManager beanManager;

    @Inject
    HostJavaCompiler hostJavaCompiler;

    @Inject
    AuditService auditService;

    CodeLanguageDetector codeLanguageDetector;
    OutputTruncator outputTruncator;

    @PostConstruct
    void init() {
        this.codeLanguageDetector = new CodeLanguageDetector(configuredPythonExe, configuredJavaExe, configuredGoExe);
        this.outputTruncator = new OutputTruncator(maxOutputChars);
    }

    @ConfigProperty(name = "amsha.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir = "./data/workspaces";

    @ConfigProperty(name = "amsha.sandbox.python-executable")
    Optional<String> configuredPythonExe = Optional.empty();

    @ConfigProperty(name = "amsha.sandbox.go-executable")
    Optional<String> configuredGoExe = Optional.empty();

    @ConfigProperty(name = "amsha.sandbox.java-executable")
    Optional<String> configuredJavaExe = Optional.empty();

    @Override
    public ToolResponse healthCheck() {
        if (getSandboxPort() == null) {
            return ToolResponse.failure(503, "Sandbox execution environment is unavailable.");
        }
        return ToolResponse.success("Sandbox execution environment is initialized.");
    }

    @Override
    public String name() {
        return ToolCapability.SANDBOX_EXEC.toolName();
    }

    @Override
    public String description() {
        return ToolCapability.SANDBOX_EXEC.description();
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.EXECUTE_SHELL;
    }

    @Override
    public boolean requiresSandbox() {
        return true;
    }

    public ToolCapability capability() {
        return ToolCapability.SANDBOX_EXEC;
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("code", "The source code to execute")
                .addStringProperty("command", "Alias for 'code'. The command or script to execute.")
                .addStringProperty(
                        "language", "The programming language (" + CodeLanguageDetector.SUPPORTED_LANGUAGES_HINT + ").")
                .required("code", "language")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // sandboxCommand() - returns the command that runs INSIDE the Podman container.
    //
    // Java path: uses java --source with --enable-preview via sh -c that
    //            auto-detects the JDK major version at runtime (H4).
    //            This works across JDK 21 (container) and JDK 25 (host fallback)
    //            since --enable-preview requires --source matching the current
    //            release. The embedded /workspace path is handled by
    //            rewriteContainerPaths() in ProcessIsolationExecutor.
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public List<String> sandboxCommand(ToolExecutionContext ctx, Map<String, Object> params) {
        normalizeParams(params);
        String code = (String) params.get("code");
        if (code == null) throw new IllegalArgumentException("Parameter 'code' is required.");
        String lang = (String) params.get("language");
        if (lang == null || lang.isBlank()) {
            throw new IllegalArgumentException("Parameter 'language' is required.");
        }
        lang = lang.toLowerCase(Locale.ROOT);

        auditService.record(
                ctx.sessionId(),
                ctx.userId(),
                ctx.agentRole(),
                SecurityEventType.TOOL_EXECUTED,
                "sandbox_exec lang=" + lang + " codeLen=" + code.length());

        String violation = SandboxCodeSafetyScanner.findViolation(lang, code);
        if (violation != null) {
            log.warnf("[SandboxExecTool] Blocked dangerous pattern in session %s: %s", ctx.sessionId(), violation);
            throw new IllegalArgumentException("🚫 Execution blocked: " + violation);
        }

        return switch (lang) {
            case LanguageIds.JAVA -> {
                String className = codeLanguageDetector.extractJavaClassName(code);
                String srcPath = CONTAINER_WORKSPACE + "/" + className + ".java";
                yield List.of(
                        LanguageIds.SH,
                        "-c",
                        "ver=$(java -version 2>&1); ver=${ver#*\\\"}; ver=${ver%%.*} && "
                                + "exec java --enable-preview --source \"$ver\" " + srcPath);
            }
            case LanguageIds.PYTHON, LanguageIds.PYTHON3 ->
                List.of(LanguageIds.PYTHON3, CONTAINER_WORKSPACE + "/script.py");
            case LanguageIds.BASH -> List.of(LanguageIds.BASH, CONTAINER_WORKSPACE + "/script.sh");
            case LanguageIds.GO -> List.of(LanguageIds.GO, "run", CONTAINER_WORKSPACE + "/main.go");
            case LanguageIds.NODE -> List.of(LanguageIds.NODE, CONTAINER_WORKSPACE + "/script.js");
            default -> codeLanguageDetector.buildCommand(lang, code);
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // prepareWorkspace() - runs on the HOST before the container is launched.
    //
    // Java: write .java file only (H4). Compilation now happens inside the
    //       container via java --source in sandboxCommand(). The host-side javac
    //       call is removed to prevent sandbox boundary bypass.
    //       executeJavaFallback() still compiles on host for the non-sandbox path.
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void prepareWorkspace(Path workspaceRoot, Map<String, Object> params) throws IOException {
        normalizeParams(params);
        String lang = (String) params.get("language");
        if (lang == null || lang.isBlank()) throw new IllegalArgumentException("Parameter 'language' is required.");
        String code = (String) params.get("code");
        if (code == null) return;
        lang = lang.toLowerCase(Locale.ROOT);

        if (LanguageIds.JAVA.equals(lang)) {
            if (!codeLanguageDetector.containsTypeDeclaration(code)) {
                code = "public class Main {\n    public static void main(String[] args) {\n" + code.indent(8)
                        + "    }\n}\n";
            }
            Files.createDirectories(workspaceRoot);
            String className = codeLanguageDetector.extractJavaClassName(code);
            Path javaFile = workspaceRoot.resolve(className + ".java");
            Files.writeString(javaFile, code, StandardCharsets.UTF_8);

        } else if (LanguageIds.PYTHON.equals(lang) || LanguageIds.PYTHON3.equals(lang)) {
            // Write to file - avoids shell quote-stripping of f-strings,
            // double quotes, and special chars when passed via -c to Podman.
            Files.createDirectories(workspaceRoot);
            Files.writeString(workspaceRoot.resolve("script.py"), code, StandardCharsets.UTF_8);

        } else if (LanguageIds.GO.equals(lang)) {
            Files.createDirectories(workspaceRoot);
            Files.writeString(workspaceRoot.resolve("main.go"), code, StandardCharsets.UTF_8);
        } else if (LanguageIds.NODE.equals(lang)) {
            Files.createDirectories(workspaceRoot);
            Files.writeString(workspaceRoot.resolve("script.js"), code, StandardCharsets.UTF_8);
        } else if (LanguageIds.BASH.equals(lang)) {
            Files.createDirectories(workspaceRoot);
            Files.writeString(workspaceRoot.resolve("script.sh"), code, StandardCharsets.UTF_8);
        }
    }

    @Override
    public String postProcessOutput(String rawOutput, Map<String, Object> params) {
        return outputTruncator.truncate(rawOutput);
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String sessionId = ctx.sessionId();

        try {
            normalizeParams(params);
            String code = (String) params.get("code");
            String lang = (String) params.get("language");
            if (code == null) return ToolResponse.failure(1, "Parameter 'code' is required.");
            if (lang == null || lang.isBlank()) {
                return ToolResponse.failure(1, "Parameter 'language' is required.");
            }
            lang = lang.toLowerCase(Locale.ROOT);

            String violation = SandboxCodeSafetyScanner.findViolation(lang, code);
            if (violation != null) return ToolResponse.failure(1, "🚫 Execution blocked: " + violation);

            if (LanguageIds.JAVA.equals(lang) && !codeLanguageDetector.containsTypeDeclaration(code)) {
                code = "public class Main {\n    public static void main(String[] args) {\n" + code.indent(8)
                        + "    }\n}\n";
            }

            // Secrets are intentionally empty on the direct-execution fallback path.
            // The sandboxCommand() → PodmanContainerExecutor path injects secrets
            // securely via SandboxSecretResolver + tmpfs transport.
            // DirectExecutor has no secure secret injection mechanism.
            if (params.containsKey("secrets")) {
                log.warnf(
                        "[SandboxExecTool] 'secrets' parameter ignored on direct host fallback - "
                                + "Podman unavailable. session=%s",
                        sessionId);
            }
            Map<String, String> requiredSecrets = Map.of();

            Path workspaceRoot = Path.of(workspaceRootDir, codeLanguageDetector.toFsName(sessionId));

            return switch (lang) {
                case LanguageIds.JAVA -> executeJavaFallback(sessionId, workspaceRoot, code, requiredSecrets);
                case LanguageIds.GO -> executeGo(sessionId, workspaceRoot, code, requiredSecrets);
                default -> executeInterpreted(sessionId, workspaceRoot, lang, code, params, requiredSecrets);
            };
        } catch (BudgetExceededException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            return ToolResponse.failure(1, e.getMessage());
        } catch (TimeoutException e) {
            log.errorf(
                    "[SandboxExecTool] Execution timed out (type=%s)",
                    e.getClass().getSimpleName());
            return offline();
        } catch (Exception e) {
            if (e.getCause() instanceof TimeoutException
                    || (e.getMessage() != null && e.getMessage().contains("timed out"))) {
                log.errorf(
                        "[SandboxExecTool] Execution timed out (type=%s)",
                        e.getClass().getSimpleName());
                return offline();
            }
            if (e.getClass().getSimpleName().equals("ToolExecutionException")) {
                return ToolResponse.failure(1, SandboxFailureMessages.SANDBOX_EXECUTION_FAILED_MESSAGE);
            }
            log.errorf(
                    "[SandboxExecTool] Execution failed (type=%s)", e.getClass().getSimpleName());
            return offline();
        }
    }

    private static ToolResponse offline() {
        return ToolResponse.infraFailure(SANDBOX_OFFLINE_MESSAGE);
    }

    private ToolResponse executeJavaFallback(
            String sessionId, Path workspaceRoot, String code, Map<String, String> secrets) throws Exception {

        Files.createDirectories(workspaceRoot);
        String className = codeLanguageDetector.extractJavaClassName(code);
        Path javaFile = workspaceRoot.resolve(className + ".java");
        Files.writeString(javaFile, code, StandardCharsets.UTF_8);

        try {
            hostJavaCompiler.compile(javaFile, workspaceRoot);
        } catch (IOException compileError) {
            return ToolResponse.failure(1, SandboxFailureMessages.SANDBOX_EXECUTION_FAILED_MESSAGE);
        }

        String javaExe = codeLanguageDetector.resolveJavaExecutable();
        List<String> runCmd = List.of(javaExe, "-cp", workspaceRoot.toString(), className);

        SandboxExecutionPort port = getSandboxPort();
        if (port == null) {
            return offline();
        }

        SandboxResult result = port.executeSandboxed(sessionId, workspaceRoot, runCmd, secrets);
        if (result == null || result.isInfraFailure()) {
            log.errorf("[SandboxExecTool] Infra failure (java-fallback) sessionId=%s", sessionId);
            return offline();
        }
        if (!result.success()) {
            return ToolResponse.failure(1, SandboxFailureMessages.SANDBOX_EXECUTION_FAILED_MESSAGE);
        }
        return ToolResponse.success(postProcessOutput(result.output(), Map.of()));
    }

    private SandboxExecutionPort getSandboxPort() {
        return CoreSpiResolutionValidator.resolveOptional(beanManager, SandboxExecutionPort.class)
                .orElse(null);
    }

    private ToolResponse executeInterpreted(
            String sessionId,
            Path workspaceRoot,
            String lang,
            String code,
            Map<String, Object> params,
            Map<String, String> secrets)
            throws Exception {

        List<String> commandList = new ArrayList<>(codeLanguageDetector.buildCommand(lang, code));
        Object argsObj = params.get("args");
        if (argsObj instanceof List<?> extraArgs) {
            for (Object arg : extraArgs) commandList.add(arg.toString());
        }

        SandboxExecutionPort port = getSandboxPort();
        if (port == null) {
            return offline();
        }

        SandboxResult result = port.executeSandboxed(sessionId, workspaceRoot, commandList, secrets);
        if (result == null || result.isInfraFailure()) {
            log.errorf("[SandboxExecTool] Infra failure (interpreted/%s) session=%s", lang, sessionId);
            return offline();
        }
        if (!result.success()) {
            return ToolResponse.failure(1, SandboxFailureMessages.SANDBOX_EXECUTION_FAILED_MESSAGE);
        }
        return ToolResponse.success(postProcessOutput(result.output(), params));
    }

    private ToolResponse executeGo(String sessionId, Path workspaceRoot, String code, Map<String, String> secrets)
            throws Exception {
        Files.createDirectories(workspaceRoot);
        Files.writeString(workspaceRoot.resolve("main.go"), code, StandardCharsets.UTF_8);

        String goExe = codeLanguageDetector.resolveGoExecutable();
        List<String> runCmd = List.of(goExe, "run", CONTAINER_WORKSPACE + "/main.go");
        try {
            SandboxExecutionPort port = getSandboxPort();
            if (port == null) {
                return offline();
            }

            SandboxResult result = port.executeSandboxed(sessionId, workspaceRoot, runCmd, secrets);
            if (result == null || result.isInfraFailure()) {
                log.errorf("[SandboxExecTool] Infra failure (go) session=%s", sessionId);
                return offline();
            }
            if (!result.success()) {
                return ToolResponse.failure(1, SandboxFailureMessages.SANDBOX_EXECUTION_FAILED_MESSAGE);
            }
            return ToolResponse.success(postProcessOutput(result.output(), Map.of()));
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("No such file or directory")) {
                return ToolResponse.failure(
                        1,
                        "Go runtime not found on this system. "
                                + "Install Go or rephrase the task to use Python/Node instead.");
            }
            throw e;
        }
    }

    List<String> buildCommand(String lang, String code) {
        return codeLanguageDetector.buildCommand(lang, code);
    }

    String extractJavaClassName(String code) {
        return codeLanguageDetector.extractJavaClassName(code);
    }

    private void normalizeParams(Map<String, Object> params) {
        if (params == null) return;
        if (!params.containsKey("code") && params.containsKey("command")) {
            params.put("code", params.get("command"));
        }
        ensureUnwrapped(params);
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
                log.debugf(
                        "Non-fatal parameter unwrapping failure (type=%s)",
                        e.getClass().getSimpleName());
                // ignore and proceed with raw code if parsing fails
            }
        }
    }
}
