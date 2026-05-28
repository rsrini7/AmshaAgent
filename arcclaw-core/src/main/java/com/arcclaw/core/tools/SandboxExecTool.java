package com.arcclaw.core.tools;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.sandbox.ProcessIsolationExecutor;
import com.arcclaw.core.sandbox.SandboxCodeSafetyScanner;
import com.arcclaw.core.sandbox.SandboxResult;
import com.arcclaw.core.security.ToolOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * SandboxExecTool - executes user-submitted code inside an isolated Podman container.
 */
@ApplicationScoped
@ToolMetadata(
        capabilityId = "sandbox_exec",
        description = "Execute code in an isolated sandbox",
        defaultRoles = {"coder", "debugger"})
public class SandboxExecTool implements AgentTool {

    private static final Logger log = Logger.getLogger(SandboxExecTool.class);

    @ConfigProperty(name = "arcclaw.sandbox.max-output-chars", defaultValue = "8192")
    int maxOutputChars;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Container-side mount point for the workspace directory.
    // ProcessIsolationExecutor mounts workspaceRoot here read-only.
    // Must stay in sync with the -v flag in PodmanContainerExecutor.
    private static final String CONTAINER_WORKSPACE = "/workspace";

    // ── Single source of truth for the infra-failure user message.
    // Never copy-paste this string - always reference this constant.
    // Logs contain the raw reason; the LLM/user only ever sees this sanitized message.
    private String buildSandboxOfflineMsg(String reason) {
        String msg = "SANDBOX_OFFLINE: System Error - Podman sandbox execution failed. ";
        if (isolationExecutor.isAllowInsecureFallback()) {
            msg += "Direct host fallback also failed: " + reason + ". ";
        } else {
            msg += "The code was NOT run. Inform the user gracefully that the execution "
                    + "environment is currently offline, or set "
                    + "ARCCLAW_SANDBOX_ALLOW_INSECURE_FALLBACK=true to allow insecure fallback.";
        }
        return msg;
    }

    @Inject
    ProcessIsolationExecutor isolationExecutor;

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
                .addStringProperty("language", "The programming language (python, java, go, node, bash)")
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
        ensureUnwrapped(params);
        String code = (String) params.get("code");
        String lang = (String) params.get("language");
        if (code == null) throw new IllegalArgumentException("Parameter 'code' is required.");
        if (lang == null) throw new IllegalArgumentException("Parameter 'language' is required.");

        lang = lang.toLowerCase();

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
            case "java" -> {
                String className = codeLanguageDetector.extractJavaClassName(code);
                String srcPath = CONTAINER_WORKSPACE + "/" + className + ".java";
                yield List.of(
                        "sh",
                        "-c",
                        "ver=$(java -version 2>&1); ver=${ver#*\\\"}; ver=${ver%%.*} && "
                                + "exec java --enable-preview --source \"$ver\" " + srcPath);
            }
            case "python", "python3" -> List.of("python3", CONTAINER_WORKSPACE + "/script.py");
            case "bash" -> List.of("bash", CONTAINER_WORKSPACE + "/script.sh");
            case "go" -> List.of("go", "run", CONTAINER_WORKSPACE + "/main.go");
            case "node" -> List.of("node", CONTAINER_WORKSPACE + "/script.js");
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
        ensureUnwrapped(params);
        String lang = (String) params.get("language");
        String code = (String) params.get("code");
        if (lang == null || code == null) return;
        lang = lang.toLowerCase();

        if ("java".equals(lang)) {
            if (!codeLanguageDetector.containsTypeDeclaration(code)) {
                code = "public class Main {\n    public static void main(String[] args) {\n" + code.indent(8)
                        + "    }\n}\n";
            }
            Files.createDirectories(workspaceRoot);
            String className = codeLanguageDetector.extractJavaClassName(code);
            Path javaFile = workspaceRoot.resolve(className + ".java");
            Files.writeString(javaFile, code, StandardCharsets.UTF_8);

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

    @Override
    public String postProcessOutput(String rawOutput, Map<String, Object> params) {
        return outputTruncator.truncate(rawOutput);
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

            if ("java".equals(lang) && !codeLanguageDetector.containsTypeDeclaration(code)) {
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
                case "java" -> executeJavaFallback(sessionId, workspaceRoot, code, requiredSecrets);
                case "go" -> executeGo(sessionId, workspaceRoot, code, requiredSecrets);
                default -> executeInterpreted(sessionId, workspaceRoot, lang, code, params, requiredSecrets);
            };
        } catch (BudgetExceededException e) {
            throw e;
        } catch (TimeoutException e) {
            log.errorf("[SandboxExecTool] Execution timed out: %s", LogUtil.rootMessage(e));
            return ToolResponse.infraFailure(buildSandboxOfflineMsg("Timeout"));
        } catch (Exception e) {
            String msg = e.getMessage();
            return ToolResponse.failure(
                    1, msg != null && !msg.isBlank() ? msg : "Sandbox execution failed with no details.");
        }
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
            return ToolResponse.failure(1, compileError.getMessage());
        }

        String javaExe = codeLanguageDetector.resolveJavaExecutable();
        List<String> runCmd = List.of(javaExe, "-cp", workspaceRoot.toString(), className);

        SandboxResult result = isolationExecutor.executeSandboxed(sessionId, workspaceRoot, runCmd, secrets);
        if (result == null || result.isInfraFailure()) {
            String reason = result != null ? result.reason() : "Sandbox executor returned null";
            log.errorf("[SandboxExecTool] Infra failure (java-fallback) sessionId=%s: %s", sessionId, reason);
            return ToolResponse.infraFailure(buildSandboxOfflineMsg(reason));
        }
        if (!result.success()) {
            return ToolResponse.failure(1, result.reason());
        }
        return ToolResponse.success(postProcessOutput(result.output(), Map.of()));
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

        SandboxResult result = isolationExecutor.executeSandboxed(sessionId, workspaceRoot, commandList, secrets);
        if (result == null || result.isInfraFailure()) {
            String reason = result != null ? result.reason() : "Sandbox executor returned null";
            log.errorf("[SandboxExecTool] Infra failure (interpreted/%s) session=%s: %s", lang, sessionId, reason);
            return ToolResponse.infraFailure(buildSandboxOfflineMsg(reason));
        }
        if (!result.success()) {
            return ToolResponse.failure(1, result.reason());
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
            SandboxResult result = isolationExecutor.executeSandboxed(sessionId, workspaceRoot, runCmd, secrets);
            if (result == null || result.isInfraFailure()) {
                String reason = result != null ? result.reason() : "Sandbox executor returned null";
                log.errorf("[SandboxExecTool] Infra failure (go) session=%s: %s", sessionId, reason);
                return ToolResponse.infraFailure(buildSandboxOfflineMsg(reason));
            }
            if (!result.success()) {
                return ToolResponse.failure(1, result.reason());
            }
            return ToolResponse.success(postProcessOutput(result.output(), Map.of()));
        } catch (IOException e) {
            if (e.getMessage().contains("No such file or directory")) {
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
                log.debugf("Non-fatal (unwrapping): %s", LogUtil.rootMessage(e));
                // ignore and proceed with raw code if parsing fails
            }
        }
    }
}
