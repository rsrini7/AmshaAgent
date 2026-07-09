package com.amsha.core.tools;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.agent.role.ToolMetadata;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.sandbox.DirectExecutor;
import com.amsha.core.sandbox.ToolExecutionException;
import com.amsha.core.security.ToolOperation;
import com.amsha.core.util.HtmlArtifactUtil;

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

    @ConfigProperty(name = "amsha.tools.host-exec.max-output-chars", defaultValue = "8192")
    int maxOutputChars;

    @ConfigProperty(name = "amsha.tools.host-exec.python-executable")
    Optional<String> configuredPythonExe;

    @ConfigProperty(name = "amsha.tools.host-exec.java-executable")
    Optional<String> configuredJavaExe;

    @ConfigProperty(name = "amsha.tools.host-exec.go-executable")
    Optional<String> configuredGoExe;

    @Inject
    AuditService auditService;

    @Inject
    HostJavaCompiler hostJavaCompiler;

    @Inject
    DirectExecutor directExecutor;

    private static final Pattern BROWSER_PREVIEW_GOAL_PATTERN =
            Pattern.compile("(?i)\\b(open in browser|open browser|browser preview|default browser)\\b");
    private static final Pattern HTML_INSPECTION_PATTERN = Pattern.compile(
            "(?is)^\\s*(?:cat|less|head|tail|sed\\s+-n\\s+.*?)(?:\\s+)([^\\s;|&]+\\.(?:html?|htm))\\s*$");

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
                .addStringProperty("command", "Alias for 'code'. The command or script to execute on the host.")
                .addStringProperty("language", "The programming language (bash, java). Default: bash")
                .required("code")
                .build();
    }

    @Override
    public String postProcessOutput(String rawOutput, Map<String, Object> params) {
        String code = null;
        if (params != null && params.get("code") instanceof String s) {
            code = s;
        }
        return HtmlArtifactUtil.collapseHtmlArtifact(outputTruncator.truncate(rawOutput), code);
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String sessionId = ctx.sessionId();
        try {
            if (params == null) return ToolResponse.failure(1, "Parameters are required.");
            if (!params.containsKey("code") && params.containsKey("command")) {
                params.put("code", params.get("command"));
            }
            String code = (String) params.get("code");
            String lang = (String) params.getOrDefault("language", "bash");
            if (code == null) return ToolResponse.failure(1, "Parameter 'code' is required.");
            if (lang == null || lang.isBlank()) {
                lang = "bash";
            }
            lang = lang.toLowerCase(Locale.ROOT);

            auditService.record(
                    sessionId,
                    ctx.userId(),
                    ctx.agentRole(),
                    SecurityEventType.TOOL_EXECUTED,
                    "host_exec lang=" + lang + " codeLen=" + code.length());

            Path workspaceRoot = resolveWorkspaceRoot(ctx);
            if (ctx.skillHome() != null && !ctx.skillHome().isBlank()) {
                Files.createDirectories(workspaceRoot.resolve("output"));
            }

            return switch (lang) {
                case "bash" -> executeBash(ctx, workspaceRoot, code);
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

    private Path resolveWorkspaceRoot(ToolExecutionContext ctx) {
        if (ctx != null && ctx.skillHome() != null && !ctx.skillHome().isBlank()) {
            return Path.of(ctx.skillHome());
        }
        return Path.of(workspaceRootDir, codeLanguageDetector.toFsName(ctx.sessionId()));
    }

    private ToolResponse executeBash(ToolExecutionContext ctx, Path workspaceRoot, String code) throws Exception {
        Files.createDirectories(workspaceRoot);
        BrowserPreviewPlan browserPreview = rewriteBrowserPreviewIfNeeded(ctx, code);
        String effectiveCode = browserPreview.code();

        Path scriptPath = workspaceRoot.resolve("script.sh");
        Files.writeString(scriptPath, effectiveCode, StandardCharsets.UTF_8);
        setExecutable(scriptPath);

        List<String> command = List.of("bash", scriptPath.toString());
        // DirectExecutor expects Map<String, byte[]> for secrets; host_exec intentionally
        // does NOT propagate secrets (mirrors SandboxExecTool.executeJavaFallback behaviour).
        String rawOutput = directExecutor.executeDirectly(workspaceRoot, command, Map.of());
        if (browserPreview.targetPath() != null) {
            String previewTarget = browserPreview.targetPath();
            Path mirrored = mirrorBrowserArtifactIfNeeded(ctx, workspaceRoot, previewTarget);
            return ToolResponse.success(
                    "Opened browser preview: " + (mirrored != null ? mirrored.toString() : previewTarget));
        }
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

    BrowserPreviewPlan rewriteBrowserPreviewIfNeeded(ToolExecutionContext ctx, String code) {
        if (ctx == null || code == null || code.isBlank()) {
            return new BrowserPreviewPlan(code, null);
        }

        String goal = ctx.rawUserGoal();
        if (goal == null || !BROWSER_PREVIEW_GOAL_PATTERN.matcher(goal).find()) {
            return new BrowserPreviewPlan(code, null);
        }

        if (containsBrowserOpener(code)) {
            return new BrowserPreviewPlan(code, null);
        }

        Matcher inspectionMatcher = HTML_INSPECTION_PATTERN.matcher(code);
        if (!inspectionMatcher.matches()) {
            return new BrowserPreviewPlan(code, null);
        }

        String htmlPath = inspectionMatcher.group(1);
        if (htmlPath == null || htmlPath.isBlank()) {
            return new BrowserPreviewPlan(code, null);
        }

        // Template files are reference material, not the final browser artifact.
        // Let the model read them, but never auto-open them as the rendered preview.
        if (htmlPath.contains("/templates/")) {
            log.debugf("[HostExec] Browser preview skip for template path %s", htmlPath.trim());
            return new BrowserPreviewPlan(code, null);
        }

        String openerScript = buildBrowserOpenScript(htmlPath.trim());
        log.infof("[HostExec] Auto-opening browser preview for %s", htmlPath.trim());
        return new BrowserPreviewPlan(openerScript, htmlPath.trim());
    }

    private boolean containsBrowserOpener(String code) {
        String lower = code.toLowerCase(Locale.ROOT);
        return lower.contains("xdg-open") || lower.contains("open ") || lower.contains("start ");
    }

    private String buildBrowserOpenScript(String htmlPath) {
        String quoted = shellQuote(htmlPath);
        return """
                if command -v open >/dev/null 2>&1; then
                  open %s
                  echo "Opened browser preview: %s"
                elif command -v xdg-open >/dev/null 2>&1; then
                  xdg-open %s
                  echo "Opened browser preview: %s"
                else
                  echo "Browser opener not available for %s" >&2
                  exit 1
                fi
                """.formatted(quoted, quoted, quoted, quoted, quoted);
    }

    private Path mirrorBrowserArtifactIfNeeded(ToolExecutionContext ctx, Path workspaceRoot, String targetPath)
            throws IOException {
        if (ctx == null
                || ctx.skillHome() == null
                || ctx.skillHome().isBlank()
                || targetPath == null
                || targetPath.isBlank()) {
            return null;
        }

        Path skillHome = Path.of(ctx.skillHome());
        Path outputDir = skillHome.resolve("output");
        Files.createDirectories(outputDir);

        Path source = Path.of(targetPath);
        if (!source.isAbsolute()) {
            source = workspaceRoot.resolve(source).normalize();
        }
        if (!Files.exists(source) || Files.isDirectory(source)) {
            return null;
        }

        Path normalizedSource = source.normalize();
        Path normalizedOutputDir = outputDir.normalize();
        if (normalizedSource.startsWith(normalizedOutputDir)) {
            return normalizedSource;
        }

        Path mirrored = outputDir.resolve(source.getFileName().toString());
        Files.copy(source, mirrored, StandardCopyOption.REPLACE_EXISTING);
        return mirrored;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    record BrowserPreviewPlan(String code, String targetPath) {}
}
