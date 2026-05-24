package com.arcclaw.tool.playwright;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.IdempotencyMode;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
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

    @Inject
    PlaywrightMcpHelper mcpHelper;

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
        return "Full browser automation via @playwright/cli daemon. " +
               "Actions: navigate (raw HTML), extract_text (visible text, default), screenshot (PNG), " +
               "click (CSS selector), fill (input field), wait (element to appear), eval (JavaScript). " +
               "Use for JS-heavy sites. For simple static pages, use web_fetch instead.";
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addStringProperty("url",
                "The full URL to navigate to. Must begin with http:// or https://. " +
                "Required for navigate/extract_text/screenshot. Optional for click/fill/wait/eval (uses current page).")
            .addEnumProperty("action",
                List.of("navigate", "extract_text", "screenshot", "click", "fill", "wait", "eval"),
                "navigate = return full raw HTML; " +
                "extract_text = return visible body text (default); " +
                "screenshot = return base64-encoded PNG; " +
                "click = click an element by CSS selector (requires 'selector'); " +
                "fill = fill an input field (requires 'selector' and 'value'); " +
                "wait = wait for element to appear (requires 'selector', optional 'timeoutMs'); " +
                "eval = evaluate JavaScript expression (requires 'script').")
            .addStringProperty("selector",
                "CSS selector for click/fill/wait actions. Example: 'button.submit', '#login-form input[name=email]'.")
            .addStringProperty("value",
                "Value to fill into an input field (used with action=fill).")
            .addStringProperty("script",
                "JavaScript expression to evaluate (used with action=eval). Example: 'document.title', 'document.querySelectorAll(\"a\").length'.")
            .addIntegerProperty("timeoutMs",
                "Timeout in milliseconds for wait action (default: 5000).")
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

        String action = params.getOrDefault("action", "extract_text").toString();
        String session = sessionName(ctx.sessionId());

        // Actions that require a URL
        boolean needsUrl = switch (action) {
            case "click", "fill", "wait", "eval" -> false;
            default -> true;
        };

        String url = (String) params.get("url");
        if (needsUrl) {
            if (url == null || url.isBlank()) {
                return ToolResponse.failure(400, "Parameter 'url' is required for action '" + action + "'.");
            }
            if (!SAFE_URL_PATTERN.matcher(url.trim()).matches()) {
                return ToolResponse.failure(400,
                    "Unsupported URL scheme. Only http:// and https:// are allowed. Got: " + url);
            }
        }

        // Validate action-specific parameters
        String selector = (String) params.get("selector");
        String value = (String) params.get("value");
        String script = (String) params.get("script");

        if (("click".equals(action) || "fill".equals(action) || "wait".equals(action))
            && (selector == null || selector.isBlank())) {
            return ToolResponse.failure(400, "Parameter 'selector' is required for action '" + action + "'.");
        }
        if ("fill".equals(action) && (value == null || value.isBlank())) {
            return ToolResponse.failure(400, "Parameter 'value' is required for action 'fill'.");
        }
        if ("eval".equals(action) && (script == null || script.isBlank())) {
            return ToolResponse.failure(400, "Parameter 'script' is required for action 'eval'.");
        }

        // Try MCP first if enabled and connected
        mcpHelper.ensureConnected();
        if (mcpHelper.isAvailable()) {
            try {
                return mcpHelper.invoke(ctx, params);
            } catch (Exception e) {
                log.warnf("MCP execution failed, falling back to CLI: %s", LogUtil.rootMessage(e));
            }
        }

        try {
            activeSessions.add(session);

            // Navigate to URL if provided
            if (url != null && !url.isBlank()) {
                if (headless) {
                    execWithSession(session, "open", url.trim());
                } else {
                    execWithSession(session, "open", url.trim(), "--headed");
                }
            }

            String result = switch (action) {
                case "screenshot" -> takeScreenshot(session);
                case "navigate"   -> execWithSession(session, "eval", "() => document.documentElement.outerHTML");
                case "click"      -> doClick(session, selector);
                case "fill"       -> doFill(session, selector, value);
                case "wait"       -> doWait(session, selector, params);
                case "eval"       -> doEval(session, script);
                default           -> extractText(session);
            };

            if (!"screenshot".equals(action) && result.length() > MAX_CONTENT_CHARS) {
                result = truncateAtSentence(result, MAX_CONTENT_CHARS) + "\n[content truncated]";
            }

            auditService.record(ctx.sessionId(), userId, ctx.agentRole(),
                SecurityEventType.TOOL_EXECUTED, "Browser " + action + (url != null ? ": " + url : ""));

            return ToolResponse.success(result);

        } catch (Exception e) {
            log.errorf("Playwright CLI execution failed: %s\n%s", LogUtil.rootMessage(e), LogUtil.trimmedStack(e));
            return ToolResponse.failure(502, "Browser action '" + action + "' failed: " + LogUtil.rootMessage(e));
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

    private String doClick(String session, String selector) {
        String sel = escapeJsString(selector);
        String script = "() => { const el = document.querySelector('" + sel + "'); " +
                        "if (!el) throw new Error('Element not found'); " +
                        "el.click(); return 'Clicked: ' + el.tagName + (el.id ? '#' + el.id : '') + (el.className ? '.' + el.className.split(' ')[0] : ''); }";
        return execWithSession(session, "eval", script).trim();
    }

    private String doFill(String session, String selector, String value) {
        String sel = escapeJsString(selector);
        String val = escapeJsString(value);
        String script = "() => { const el = document.querySelector('" + sel + "'); " +
                        "if (!el) throw new Error('Element not found'); " +
                        "el.focus(); el.value = '" + val + "'; " +
                        "el.dispatchEvent(new Event('input', {bubbles: true})); " +
                        "el.dispatchEvent(new Event('change', {bubbles: true})); " +
                        "return 'Filled ' + el.tagName + ' with ' + el.value.length + ' chars'; }";
        return execWithSession(session, "eval", script).trim();
    }

    private String doWait(String session, String selector, Map<String, Object> params) {
        int timeoutMs = 5000;
        if (params.containsKey("timeoutMs")) {
            Object tObj = params.get("timeoutMs");
            if (tObj instanceof Number n) {
                timeoutMs = Math.min(30000, Math.max(1000, n.intValue()));
            }
        }
        // Use playwright-cli's built-in wait command with -- separator to prevent flag injection
        try {
            return execWithSession(session, "wait", "--", selector, "--timeout=" + timeoutMs).trim();
        } catch (Exception e) {
            // Fallback: poll via JS
            String sel = escapeJsString(selector);
            String script = "() => { const el = document.querySelector('" + sel + "'); " +
                            "if (!el) throw new Error('Element not found'); " +
                            "return 'Element found: ' + el.tagName; }";
            return execWithSession(session, "eval", script).trim();
        }
    }

    private String doEval(String session, String script) {
        String trimmed = script.trim();
        boolean isCallable = trimmed.startsWith("()") || trimmed.startsWith("function")
            || trimmed.startsWith("async ") || trimmed.startsWith("async\t");
        String wrapped = isCallable ? trimmed : "() => (" + trimmed + ")";
        return execWithSession(session, "eval", wrapped).trim();
    }

    private String exec(String command, String... args) {
        return execWithSession(null, command, args);
    }

    private String execWithSession(String session, String command, String... args) {
        try {
            return execWithSessionOnce(session, command, args);
        } catch (Exception e) {
            // Session may have expired (HITL pause, GC, etc.) — close stale session and retry once
            if (session != null) {
                log.debugf("playwright-cli first attempt failed, closing stale session: %s", LogUtil.rootMessage(e));
                try { execWithSessionOnce(null, "close-session", session); } catch (Exception _) { }
            }
            return execWithSessionOnce(session, command, args);
        }
    }

    private String execWithSessionOnce(String session, String command, String... args) {
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
                String firstLine = output.lines().findFirst().orElse(output.trim());
                throw new RuntimeException("playwright-cli exit " + exit + ": " + firstLine);
            }
            return output;
        } catch (IOException e) {
            throw new RuntimeException(
                "playwright-cli not found. Install: npm install -g @playwright/cli");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("playwright-cli interrupted");
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
        if (sessionId == null) return "arcclaw-default";
        // Use SHA-256 hash to avoid socket path length limits and EADDRINUSE
        // from delegated session IDs that contain colons and grow unbounded.
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sessionId.getBytes(StandardCharsets.UTF_8));
            // First 16 hex chars = 64 bits — enough uniqueness for session isolation
            String hex = bytesToHex(hash).substring(0, 16);
            return "arcclaw-" + hex;
        } catch (Exception e) {
            return "arcclaw-" + sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /** Escapes a string for use as a single-quoted JavaScript string literal. */
    private static String escapeJsString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\x%02x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
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
