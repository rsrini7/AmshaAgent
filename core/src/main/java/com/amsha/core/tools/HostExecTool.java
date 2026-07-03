package com.amsha.core.tools;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.agent.role.ToolMetadata;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.sandbox.DirectExecutor;
import com.amsha.core.sandbox.ToolExecutionException;
import com.amsha.core.security.ToolOperation;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * HostExecTool - executes user-submitted code directly on the host (no Podman sandbox).
 * <p>
 * This tool is gated by HITL approval at the {@link ToolGovernanceGuard} layer (see
 * {@code ToolOperation.EXECUTE_SHELL}'s CRITICAL risk level). The dangerous-pattern scanner
 * from {@code SandboxCodeSafetyScanner} is intentionally NOT applied here because the
 * approval gate is the safety contract for host execution.
 * <p>
 * Default roles are empty: capability must be granted explicitly via
 * {@code amsha.tools.host-exec.skills} (see Chunk 2 of the host_exec plan).
 */
@ApplicationScoped
@ToolMetadata(
        capabilityId = "host_exec",
        description = "Execute a command directly on the host (no Podman sandbox)",
        defaultRoles = {})
public class HostExecTool implements AgentTool {

    private static final Logger log = Logger.getLogger(HostExecTool.class);

    @ConfigProperty(name = "amsha.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    @ConfigProperty(name = "amsha.sandbox.max-output-chars", defaultValue = "8192")
    int maxOutputChars;

    @ConfigProperty(name = "amsha.sandbox.python-executable")
    Optional<String> configuredPythonExe;

    @ConfigProperty(name = "amsha.sandbox.java-executable")
    Optional<String> configuredJavaExe;

    @ConfigProperty(name = "amsha.sandbox.go-executable")
    Optional<String> configuredGoExe;

    @Inject
    AuditService auditService;

    @Inject
    HostJavaCompiler hostJavaCompiler;

    @Inject
    DirectExecutor directExecutor;

    CodeLanguageDetector codeLanguageDetector;
    OutputTruncator outputTruncator;

    @PostConstruct
    void init() {
        this.codeLanguageDetector = new CodeLanguageDetector(configuredPythonExe, configuredJavaExe, configuredGoExe);
        this.outputTruncator = new OutputTruncator(maxOutputChars);
    }

    @Override
    public String name() {
        return ToolCapability.HOST_EXEC.toolName();
    }

    @Override
    public String description() {
        return ToolCapability.HOST_EXEC.description();
    }

    public ToolCapability capability() {
        return ToolCapability.HOST_EXEC;
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.EXECUTE_SHELL;
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("code", "The source code to execute on the host")
                .addStringProperty("language", "The programming language (bash, java)")
                .required("code", "language")
                .build();
    }

    @Override
    public String postProcessOutput(String rawOutput, Map<String, Object> params) {
        return outputTruncator.truncate(rawOutput);
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String sessionId = ctx.sessionId();
        try {
            if (params == null) return ToolResponse.failure(1, "Parameters are required.");
            String code = (String) params.get("code");
            String lang = (String) params.get("language");
            if (lang == null) return ToolResponse.failure(1, "Parameter 'language' is required.");
            if (code == null) return ToolResponse.failure(1, "Parameter 'code' is required.");
            lang = lang.toLowerCase(Locale.ROOT);

            auditService.record(
                    sessionId,
                    ctx.userId(),
                    ctx.agentRole(),
                    SecurityEventType.TOOL_EXECUTED,
                    "host_exec lang=" + lang + " codeLen=" + code.length());

            Path workspaceRoot = Path.of(workspaceRootDir, codeLanguageDetector.toFsName(sessionId));

            return switch (lang) {
                case "bash" -> executeBash(workspaceRoot, code);
                case "java" -> executeJava(workspaceRoot, code);
                default ->
                    ToolResponse.failure(
                            1, "language not supported: " + lang + " (host_exec currently supports: bash, java)");
            };
        } catch (SecurityException e) {
            log.warnf("[HostExecTool] Security/timeout failure session=%s: %s", sessionId, e.getMessage());
            return ToolResponse.infraFailure("HOST_EXEC_OFFLINE: Tool failed or timed out: "
                    + (e.getMessage() == null ? "unknown" : e.getMessage()));
        } catch (ToolExecutionException e) {
            return ToolResponse.failure(e.exitCode() > 0 ? e.exitCode() : 1, e.getMessage());
        } catch (IOException e) {
            String msg = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage()
                    : "I/O error during host_exec: " + e.getClass().getSimpleName();
            log.errorf(e, "[HostExecTool] I/O failure session=%s", sessionId);
            return ToolResponse.failure(1, msg);
        } catch (Exception e) {
            String msg = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage()
                    : "Host execution failed with no details.";
            log.errorf(e, "[HostExecTool] Unexpected failure session=%s", sessionId);
            return ToolResponse.failure(1, msg);
        }
    }

    private ToolResponse executeBash(Path workspaceRoot, String code) throws Exception {
        Files.createDirectories(workspaceRoot);
        Path scriptPath = workspaceRoot.resolve("script.sh");
        Files.writeString(scriptPath, code, StandardCharsets.UTF_8);
        setExecutable(scriptPath);

        List<String> command = List.of("bash", scriptPath.toString());
        // DirectExecutor expects Map<String, byte[]> for secrets; host_exec intentionally
        // does NOT propagate secrets (mirrors SandboxExecTool.executeJavaFallback behaviour).
        String rawOutput = directExecutor.executeDirectly(workspaceRoot, command, Map.of());
        return ToolResponse.success(postProcessOutput(rawOutput, Map.of()));
    }

    private ToolResponse executeJava(Path workspaceRoot, String code) throws Exception {
        Files.createDirectories(workspaceRoot);
        String preparedCode = code;
        if (!codeLanguageDetector.containsTypeDeclaration(preparedCode)) {
            preparedCode = "public class Main {\n    public static void main(String[] args) {\n"
                    + preparedCode.indent(8) + "    }\n}\n";
        }
        String className = codeLanguageDetector.extractJavaClassName(preparedCode);
        Path javaFile = workspaceRoot.resolve(className + ".java");
        Files.writeString(javaFile, preparedCode, StandardCharsets.UTF_8);

        try {
            hostJavaCompiler.compile(javaFile, workspaceRoot);
        } catch (IOException compileError) {
            return ToolResponse.failure(1, compileError.getMessage());
        }

        String javaExe = codeLanguageDetector.resolveJavaExecutable();
        List<String> runCmd = List.of(javaExe, "-cp", workspaceRoot.toString(), className);
        String rawOutput = directExecutor.executeDirectly(workspaceRoot, runCmd, Map.of());
        return ToolResponse.success(postProcessOutput(rawOutput, Map.of()));
    }

    private static void setExecutable(Path scriptPath) throws IOException {
        if (scriptPath.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> perms = new HashSet<>();
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_WRITE);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_READ);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_READ);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            try {
                Files.setPosixFilePermissions(scriptPath, perms);
                return;
            } catch (UnsupportedOperationException ignored) {
                // Fall through to chmod-via-process on filesystems that don't support POSIX perms.
            }
        }
        // Non-POSIX fallback (e.g. Windows): best-effort chmod via process. Failure is non-fatal
        // because the bash command runs the script explicitly via "bash script.sh".
        try {
            new ProcessBuilder("chmod", "+x", scriptPath.toString())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        } catch (Exception ignored) {
            // Ignore - script.sh is invoked via "bash script.sh" so executable bit is not required.
        }
    }
}
