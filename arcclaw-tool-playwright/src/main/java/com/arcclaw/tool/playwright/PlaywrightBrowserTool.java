package com.arcclaw.tool.playwright;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolResponse;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class PlaywrightBrowserTool implements AgentTool {

    private static final int MAX_CONTENT_CHARS = 9092;
    private static final Pattern SAFE_URL_PATTERN =
        Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);

    @ConfigProperty(name = "arcclaw.tools.playwright.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "arcclaw.tools.playwright.timeout-ms", defaultValue = "30000")
    int timeoutMs;

    @Inject
    PlaywrightPageRunner pageRunner;

    @Inject
    AuditService auditService;

    // NOTE: WorkspaceContext is a pure static utility (ScopedValue-backed), NOT a CDI bean.
    // Use WorkspaceContext.userId(), WorkspaceContext.requireSessionId(), etc. — all static calls.

    @Override
    public String name() {
        return ToolCapability.BROWSER_NAVIGATE.toolName();
    }

    @Override
    public boolean requiresSandbox() {
        // Podman not required — runs on host directly.
        return false;
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.NETWORK_OUTBOUND;  // ← NETWORK_OUTBOUND, not NETWORK
    }

    @Override
    public boolean requiresManualApproval(Map<String, Object> params) {
        return false;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String description() {
        return "Navigates to a URL using a real headless browser (Playwright/Chromium) and " +
               "returns the visible page text content. Use for reading web pages that require " +
               "JavaScript rendering or are not accessible via plain HTTP fetch. " +
               "Supports text extraction, scoped CSS selector extraction, and PNG screenshot.";
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        // Mirror BraveWebSearchTool's builder pattern exactly
        return JsonObjectSchema.builder()
            .addStringProperty("url",
                "The full URL to navigate to. Must begin with http:// or https://.")
            .addEnumProperty("action",
                List.of("navigate", "extract_text", "screenshot"),
                "navigate = return full raw HTML; " +
                "extract_text = return visible body text, stripped of nav/footer boilerplate (default); " +
                "screenshot = return base64-encoded PNG of the viewport (capped at 1 MB).")
            .addStringProperty("selector",
                "Optional CSS selector to scope text extraction to a specific element " +
                "(e.g. 'article', 'main', '#content'). Ignored for action=navigate and action=screenshot.")
            .required("url")
            .build();
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        // Guard 1: tool disabled
        if (!enabled) {
            return ToolResponse.failure(503, "Playwright browser tool is not enabled. " +
                "Set arcclaw.tools.playwright.enabled=true to activate.");
        }

        // Guard 2: userId from WorkspaceContext (static, never from params — see BraveWebSearchTool)
        String userId = WorkspaceContext.requireUserId();
        if (userId == null || userId.isBlank()) {
            return ToolResponse.failure(403, "No active user session.");
        }

        // Guard 3: url present
        String url = (String) params.get("url");
        if (url == null || url.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'url' is required.");
        }

        // Guard 4: URL scheme — only http / https
        if (!SAFE_URL_PATTERN.matcher(url.trim()).matches()) {
            return ToolResponse.failure(400,
                "Unsupported URL scheme. Only http:// and https:// are allowed. Got: " + url);
        }

        String action   = params.getOrDefault("action", "extract_text").toString();
        String selector = (String) params.get("selector");

        try {
            String result = pageRunner.run(url.trim(), action, selector, timeoutMs);

            // Truncate text results at sentence boundary; screenshots pre-capped in runner
            if (!"screenshot".equals(action) && result.length() > MAX_CONTENT_CHARS) {
                result = truncateAtSentence(result, MAX_CONTENT_CHARS) + "\n[content truncated]";
            }

            // Audit with URL only — NEVER log page content.
            // AuditService.record() 5-arg signature: (sessionId, userId, agentRole, type, detail)
            String sessionId = WorkspaceContext.requireSessionId();
            auditService.record(sessionId, userId, WorkspaceContext.requireAgentRole(),
                SecurityEventType.TOOL_EXECUTED, "Browser navigate: " + url);

            return ToolResponse.success(result);

        } catch (Exception e) {
            return ToolResponse.failure(502, "Browser navigation failed: " + e.getMessage());
        }
    }

    /**
     * Truncates at the last sentence-ending punctuation at or before maxLen.
     * Falls back to a hard cut if no sentence boundary is found in the second half.
     */
    private String truncateAtSentence(String text, int maxLen) {
        String sub = text.substring(0, maxLen);
        int lastSentence = Math.max(
            sub.lastIndexOf('.'),
            Math.max(sub.lastIndexOf('!'), sub.lastIndexOf('?'))
        );
        return lastSentence > maxLen / 2 ? sub.substring(0, lastSentence + 1) : sub;
    }
}
