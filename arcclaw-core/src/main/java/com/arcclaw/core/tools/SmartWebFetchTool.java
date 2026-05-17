package com.arcclaw.core.tools;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.security.ToolOperation;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Lightweight web fetch tool that retrieves URL content as plain text.
 * <p>
 * Fills the gap between BraveWebSearchTool (search only) and PlaywrightCliTool
 * (heavy browser). Uses plain HTTP GET + HTML-to-text conversion — zero LLM
 * tokens, zero browser overhead.
 * <p>
 * For JavaScript-rendered pages, use PlaywrightCliTool instead.
 */
@ApplicationScoped
@ToolMetadata(
    capabilityId = "web_fetch",
    description = "Fetch a URL and return its content as plain text. " +
        "Use for reading articles, documentation, and static pages. " +
        "For JavaScript-heavy sites, use browser_navigate instead.",
    defaultRoles = {"researcher", "synthesizer", "coordinator"}
)
public class SmartWebFetchTool implements AgentTool {

    private static final int MAX_CONTENT_CHARS = 8192;
    private static final Pattern SAFE_URL_PATTERN =
        Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("[ \t]+");
    private static final Pattern MULTI_NEWLINE_PATTERN = Pattern.compile("\n{3,}");
    private static final Pattern SCRIPT_STYLE_PATTERN =
        Pattern.compile("(?is)<(script|style|noscript)[^>]*>.*?</\\1>");

    @Inject
    AuditService auditService;

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    @Override
    public String name() {
        return ToolCapability.WEB_FETCH.toolName();
    }

    @Override
    public String description() {
        return "Fetch a URL and return its content as plain text. " +
               "Use for reading articles, documentation, and static pages. " +
               "For JavaScript-heavy sites, use browser_navigate instead. " +
               "Parameters: url (required), maxChars (optional, default 8192).";
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
    public IdempotencyMode idempotencyMode() {
        return IdempotencyMode.IDEMPOTENT;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addStringProperty("url", "The full URL to fetch. Must begin with http:// or https://.")
            .addIntegerProperty("maxChars",
                "Maximum characters to return (default: 8192). Content is truncated at sentence boundaries.")
            .required("url")
            .build();
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String url = (String) params.get("url");
        if (url == null || url.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'url' is required.");
        }
        url = url.trim();
        if (!SAFE_URL_PATTERN.matcher(url).matches()) {
            return ToolResponse.failure(400,
                "Unsupported URL scheme. Only http:// and https:// are allowed. Got: " + url);
        }

        String ssrfError = rejectPrivateHost(url);
        if (ssrfError != null) {
            return ToolResponse.failure(403, ssrfError);
        }

        int maxChars = MAX_CONTENT_CHARS;
        if (params.containsKey("maxChars")) {
            Object mcObj = params.get("maxChars");
            if (mcObj instanceof Number n) {
                maxChars = Math.min(32768, Math.max(256, n.intValue()));
            }
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "text/html,text/plain,*/*")
                .header("User-Agent", "ArcClaw/1.0 (+https://github.com/arcclaw)")
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return ToolResponse.failure(response.statusCode(),
                    "HTTP " + response.statusCode() + " fetching " + url);
            }

            String contentType = response.headers().firstValue("content-type").orElse("");
            String body = response.body();
            String text;

            if (contentType.contains("text/html") || contentType.isEmpty()) {
                text = htmlToText(body);
            } else {
                text = body;
            }

            if (text.isBlank()) {
                return ToolResponse.success("The page at " + url + " returned no readable text content.");
            }

            if (text.length() > maxChars) {
                text = truncateAtSentence(text, maxChars) + "\n[content truncated at " + maxChars + " chars]";
            }

            auditService.record(ctx.sessionId(), ctx.userId(), ctx.agentRole(),
                SecurityEventType.TOOL_EXECUTED, "Web fetch: " + url);

            return ToolResponse.success("Content from " + url + ":\n\n" + text);

        } catch (IllegalArgumentException e) {
            return ToolResponse.failure(400, "Invalid URL: " + e.getMessage());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return ToolResponse.failure(502, "Failed to fetch " + url + ": " + e.getMessage());
        }
    }

    /**
     * Converts HTML to plain text by stripping tags, scripts, and styles.
     * Preserves paragraph structure with newlines.
     */
    static String htmlToText(String html) {
        if (html == null || html.isBlank()) return "";
        // Remove script/style/noscript blocks entirely
        String text = SCRIPT_STYLE_PATTERN.matcher(html).replaceAll("");
        // Convert block elements to newlines
        text = text.replaceAll("(?i)</(p|div|h[1-6]|li|tr|blockquote|br|hr)>", "\n");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        // Strip remaining tags
        text = TAG_PATTERN.matcher(text).replaceAll("");
        // Decode common HTML entities
        text = text.replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;", "'")
                   .replace("&nbsp;", " ")
                   .replace("&mdash;", "—")
                   .replace("&ndash;", "–")
                   .replace("&hellip;", "…")
                   .replace("&copy;", "©");
        // Normalize whitespace
        text = WHITESPACE_PATTERN.matcher(text).replaceAll(" ");
        text = MULTI_NEWLINE_PATTERN.matcher(text).replaceAll("\n\n");
        return text.trim();
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

    /** Returns an error message if the host resolves to a private/internal IP; null otherwise. */
    private static String rejectPrivateHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return null;

            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
                return "Access to private/internal network hosts is not allowed: " + host;
            }
        } catch (IllegalArgumentException e) {
            return "Invalid URL: " + e.getMessage();
        } catch (Exception e) {
            // DNS failure — the real request will fail anyway
        }
        return null;
    }
}
