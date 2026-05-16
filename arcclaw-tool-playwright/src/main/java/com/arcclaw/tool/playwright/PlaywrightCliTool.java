package com.arcclaw.tool.playwright;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.IdempotencyMode;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.common.util.LogUtil;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Replaces PlaywrightBrowserTool + PlaywrightPageRunner + PageContentExtractor +
 * ScreenshotCapture + NavigationStrategy with a single class that shells out to
 * the official {@code playwright-cli} daemon.
 * <p>
 * Eliminates: quarkus-playwright extension, Playwright Java library, GraalVM
 * reflect-config, driver-bundle extraction, platform driver excludes, single-thread
 * worker, 6-hour refresh scheduler, and all browser lifecycle management.
 * <p>
 * Requirements: Node.js 20+, {@code npm install -g @playwright/cli},
 * {@code playwright-cli install-browser chromium}.
 */
@ApplicationScoped
@ToolMetadata(
    capabilityId = "browser_navigate",
    description = "Headless browser navigation via @playwright/cli daemon",
    defaultRoles = {"researcher", "synthesizer"}
)
public class PlaywrightCliTool implements AgentTool {

    private static final Logger log = Logger.getLogger(PlaywrightCliTool.class);

    private static final int MAX_CONTENT_CHARS = 9092;
    private static final Pattern SAFE_URL_PATTERN =
        Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);

    @ConfigProperty(name = "arcclaw.tools.playwright.enabled", defaultValue = "false")
    boolean configEnabled;

    @ConfigProperty(name = "arcclaw.tools.playwright.timeout-ms", defaultValue = "30000")
    int timeoutMs;

    @ConfigProperty(name = "arcclaw.tools.playwright.headless", defaultValue = "true")
    boolean headless;

    @Inject
    AuditService auditService;

    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();

    @Override
    public String name() {
        return ToolCapability.BROWSER_NAVIGATE.toolName();
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.NETWORK_OUTBOUND;
    }

    @Override
    public boolean requiresManualApproval(Map<String, Object> params) {
        return false;
    }

    @Override
    public IdempotencyMode idempotencyMode() {
        return IdempotencyMode.IDEMPOTENT;
    }

    @Override
    public boolean isEnabled() {
        return configEnabled;
    }

    @Override
    public ToolResponse healthCheck() {
        if (!isEnabled()) {
            return ToolResponse.failure(503, "Playwright CLI tool is disabled via configuration.");
        }
        try {
            String version = exec("--version").trim();
            return ToolResponse.success("Playwright CLI is available: " + version);
        } catch (Exception e) {
            return ToolResponse.failure(503,
                "Playwright CLI not found. Install: npm install -g @playwright/cli && playwright-cli install-browser chromium");
        }
    }

    @Override
    public String description() {
        return "Navigates to a URL using a real headless browser (@playwright/cli daemon) and " +
               "returns the visible page text content. Supports text extraction and PNG screenshot. " +
               "Zero GraalVM overhead — uses the official @playwright/cli subprocess.";
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addStringProperty("url",
                "The full URL to navigate to. Must begin with http:// or https://.")
            .addEnumProperty("action",
                List.of("navigate", "extract_text", "screenshot"),
                "navigate = return full raw HTML; " +
                "extract_text = return visible body text (default); " +
                "screenshot = return base64-encoded PNG of the viewport.")
            .required("url")
            .build();
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        if (!isEnabled()) {
            return ToolResponse.failure(503, "Playwright CLI tool is not enabled. " +
                "Set arcclaw.tools.playwright.enabled=true to activate.");
        }

        String userId = ctx.userId();
        if (userId == null || userId.isBlank()) {
            return ToolResponse.failure(403, "No active user session.");
        }

        String url = (String) params.get("url");
        if (url == null || url.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'url' is required.");
        }
        if (!SAFE_URL_PATTERN.matcher(url.trim()).matches()) {
            return ToolResponse.failure(400,
                "Unsupported URL scheme. Only http:// and https:// are allowed. Got: " + url);
        }

        String action = params.getOrDefault("action", "extract_text").toString();
        String session = sessionName(ctx.sessionId());

        try {
            activeSessions.add(session);

            if (headless) {
                execWithSession(session, "open", url);
            } else {
                execWithSession(session, "open", url, "--headed");
            }
            String result = switch (action) {
                case "screenshot" -> takeScreenshot(session);
                case "navigate"   -> execWithSession(session, "eval", "() => document.documentElement.outerHTML");
                default           -> extractText(session);
            };

            if (!"screenshot".equals(action) && result.length() > MAX_CONTENT_CHARS) {
                result = truncateAtSentence(result, MAX_CONTENT_CHARS) + "\n[content truncated]";
            }

            auditService.record(ctx.sessionId(), userId, ctx.agentRole(),
                SecurityEventType.TOOL_EXECUTED, "Browser navigate: " + url);

            return ToolResponse.success(result);

        } catch (Exception e) {
            log.error("Playwright CLI execution failed", e);
            return ToolResponse.failure(502, "Browser navigation failed: " + e.getMessage());
        }
    }

    private String extractText(String session) {
        String text = execWithSession(session, "eval", "() => document.body.innerText").trim();
        if (!text.isBlank()) return text;
        String snapshot = execWithSession(session, "snapshot", "--raw").trim();
        return snapshot.isBlank() ? "No content found" : snapshot;
    }

    private String takeScreenshot(String session) {
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "arcclaw-screenshots");
        try {
            Files.createDirectories(tmpDir);
            Path shotFile = tmpDir.resolve("pw-" + System.currentTimeMillis() + ".png");
            execWithSession(session, "screenshot", "--full-page", "--filename=" + shotFile.toAbsolutePath());
            byte[] png = Files.readAllBytes(shotFile);
            Files.deleteIfExists(shotFile);
            return Base64.getEncoder().encodeToString(png);
        } catch (IOException e) {
            throw new RuntimeException("Screenshot failed: " + e.getMessage(), e);
        }
    }

    private String exec(String command, String... args) {
        return execWithSession(null, command, args);
    }

    private String execWithSession(String session, String command, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("playwright-cli");
        if (session != null) {
            cmd.add("-s=" + session);
        }
        cmd.add(command);
        cmd.addAll(Arrays.asList(args));

        try {
            ProcessBuilder pb = new ProcessBuilder(platformCommand(cmd));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit != 0) {
                throw new RuntimeException("playwright-cli exit " + exit + ": " + output.trim());
            }
            return output;
        } catch (IOException e) {
            throw new RuntimeException(
                "playwright-cli not found. Install: npm install -g @playwright/cli", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("playwright-cli interrupted", e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static List<String> platformCommand(List<String> cmd) {
        if (isWindows()) {
            List<String> wrapped = new ArrayList<>();
            wrapped.add("cmd.exe");
            wrapped.add("/c");
            wrapped.addAll(cmd);
            return wrapped;
        }
        return cmd;
    }

    private static String sessionName(String sessionId) {
        return "arcclaw-" + (sessionId != null ? sessionId : "default");
    }

    static String truncateAtSentence(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        String sub = text.substring(0, maxLen);
        int lastSentence = Math.max(
            sub.lastIndexOf('.'),
            Math.max(sub.lastIndexOf('!'), sub.lastIndexOf('?'))
        );
        return lastSentence > maxLen / 2 ? sub.substring(0, lastSentence + 1) : sub;
    }

    @PreDestroy
    void cleanup() {
        try {
            exec("close-all");
        } catch (Exception e) {
            log.debugf("playwright-cli cleanup: %s", LogUtil.rootMessage(e));
        }
    }
}
