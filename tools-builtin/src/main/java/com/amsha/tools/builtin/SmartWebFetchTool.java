package com.amsha.tools.builtin;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.ToolOperation;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolMetadata;
import com.amsha.tool.api.ToolResponse;

import org.jboss.logging.Logger;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.arc.Unremovable;

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
@Unremovable
@ToolMetadata(
        capabilityId = "web_fetch",
        description = "Last-resort governed fetch for an exact URL after browser_navigate fails. "
                + "Use for articles, documentation, and static pages. "
                + "For JavaScript-heavy sites, use browser_navigate first.",
        defaultRoles = {"researcher", "synthesizer", "coordinator"})
public class SmartWebFetchTool implements AgentTool {

    private static final int MAX_CONTENT_CHARS = 8192;
    private static final int MAX_BODY_BYTES = 524_288; // 512KB limit to prevent OOM
    private static final Pattern SAFE_URL_PATTERN = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("[ \t]+");
    private static final Pattern MULTI_NEWLINE_PATTERN = Pattern.compile("\n{3,}");
    private static final Pattern SCRIPT_STYLE_PATTERN = Pattern.compile("(?is)<(script|style|noscript)[^>]*>.*?</\\1>");
    private static final int MAX_FETCH_RETRIES = 2;

    private static final Logger LOG = Logger.getLogger(SmartWebFetchTool.class);

    @Inject
    AuditService auditService;

    @Inject
    GovernedTransportService governedTransport;

    GovernedTransportService resolveGovernedTransport() {
        if (governedTransport != null) {
            return governedTransport;
        }
        try {
            ArcContainer container = Arc.container();
            if (container != null) {
                InstanceHandle<GovernedTransportService> handle = container.instance(GovernedTransportService.class);
                if (handle != null && handle.isAvailable()) {
                    return handle.get();
                }
            }
        } catch (Exception _) {
            // Context not active or standalone test mode
        }
        throw new IllegalStateException("GovernedTransportService is required for web fetch egress");
    }

    @Override
    public String name() {
        return ToolCapability.WEB_FETCH.toolName();
    }

    @Override
    public String description() {
        return "Fetch a URL and return its content as plain text. "
                + "Use for reading articles, documentation, and static pages. "
                + "For JavaScript-heavy sites, use browser_navigate instead. "
                + "Parameters: url (required), maxChars (optional, default 8192).";
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
                .addIntegerProperty(
                        "maxChars",
                        "Maximum characters to return (default: 8192). Content is truncated at sentence boundaries.")
                .required("url")
                .build();
    }

    @Override
    public String validateParameters(Map<String, Object> params) {
        if (params == null || !params.containsKey("url")) {
            return "Parameter 'url' is required for web_fetch.";
        }
        Object urlObj = params.get("url");
        if (!(urlObj instanceof String url) || url.isBlank()) {
            return "Parameter 'url' is required for web_fetch.";
        }
        return null;
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String userId = (ctx != null) ? ctx.userId() : null;
        if (userId == null || userId.isBlank()) {
            return ToolResponse.failure(403, "Forbidden: userId is required for web fetch.");
        }

        String url = (String) params.get("url");
        if (url == null || url.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'url' is required.");
        }
        url = url.trim();
        if (!SAFE_URL_PATTERN.matcher(url).matches()) {
            return ToolResponse.failure(
                    400, "Unsupported URL scheme. Only http:// and https:// are allowed. Got: " + sanitizeUrl(url));
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

        Exception lastException = null;
        for (int attempt = 0; attempt <= MAX_FETCH_RETRIES; attempt++) {
            if (attempt > 0) {
                try {
                    LOG.infof(
                            "Retrying web_fetch for %s (attempt %d/%d)",
                            sanitizeUrl(url), attempt + 1, MAX_FETCH_RETRIES + 1);
                    Thread.sleep(1000L * attempt); // linear backoff: 1s, 2s
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return ToolResponse.failure(502, "Interrupted while fetching " + sanitizeUrl(url));
                }
            }
            try {
                return fetchUrl(url, maxChars, ctx);
            } catch (OutboundHttpException e) {
                int code =
                        switch (e.category()) {
                            case POLICY_DENIED, DNS_REBINDING, REDIRECT_DENIED, TLS_TRUST -> 403;
                            case HITL_REQUIRED -> 409;
                            case TIMEOUT -> 504;
                            default -> 502;
                        };
                return ToolResponse.failure(code, "Web fetch blocked: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                return ToolResponse.failure(400, "Invalid URL: " + e.getMessage());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return ToolResponse.failure(502, "Interrupted while fetching " + sanitizeUrl(url));
                }
                lastException = e;
                LOG.warnf("web_fetch attempt %d failed for %s: %s", attempt + 1, sanitizeUrl(url), e.getMessage());
            }
        }
        return ToolResponse.failure(
                502,
                "Failed to fetch " + sanitizeUrl(url) + " after " + (MAX_FETCH_RETRIES + 1) + " attempts: "
                        + (lastException != null ? lastException.getMessage() : "unknown error"));
    }

    private static String sanitizeUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) + "?[redacted]" : url;
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
        int lastSentence = Math.max(sub.lastIndexOf('.'), Math.max(sub.lastIndexOf('!'), sub.lastIndexOf('?')));
        return lastSentence > maxLen / 2 ? sub.substring(0, lastSentence + 1) : sub;
    }

    /**
     * Fetches a URL via GovernedTransportService and processes text content.
     */
    private ToolResponse fetchUrl(String url, int maxChars, ToolExecutionContext ctx) throws Exception {
        String ssrfError = rejectPrivateHost(url);
        if (ssrfError != null) {
            return ToolResponse.failure(403, ssrfError);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/html,text/plain,*/*")
                .header("User-Agent", "Amsha Agent/1.0 (+https://github.com/amsha)")
                .GET()
                .build();

        String userId = ctx.userId();
        // HITL boundary: ToolInvocationGuard owns approval for NETWORK_OUTBOUND tools and always
        // pauses before execution, so transport-level preApproved is false. TOOL_FETCH is
        // intentionally non-HITL at transport to avoid a double prompt. Connection is null by
        // design: web fetch is connection-agnostic (default SSRF/TLS/DNS-rebinding policy).
        HttpResponse<InputStream> response = resolveGovernedTransport()
                .send(
                        url,
                        OutboundPurpose.TOOL_FETCH,
                        null,
                        userId,
                        false,
                        request,
                        HttpResponse.BodyHandlers.ofInputStream());

        // Wrap the entire post-status handling in try-with-resources over the response body stream so
        // the InputStream (and its underlying connection) is always closed — including on the
        // rebind/404/4xx early-return branches that previously returned without consuming it.
        try (InputStream bodyStream = response.body()) {
            URI responseUri = response.uri();
            String responseUrl = responseUri.toString();
            int statusCode = response.statusCode();

            // Post-response IP validation: catch DNS rebinding + redirects to internal IPs
            String rebindError = rejectPrivateHost(responseUrl);
            if (rebindError != null) {
                return ToolResponse.failure(403, rebindError);
            }

            if (statusCode == 404) {
                return ToolResponse.failure(
                        404,
                        "HTTP 404 Not Found fetching " + sanitizeUrl(url)
                                + ". The page does not exist. Do NOT guess or hallucinate URLs. Search the web for actual links, or ask the user to provide the direct URL.");
            }

            if (statusCode >= 400) {
                return ToolResponse.failure(statusCode, "HTTP " + statusCode + " fetching " + sanitizeUrl(url));
            }

            if (bodyStream == null) {
                return ToolResponse.success("The page at " + sanitizeUrl(url) + " returned no readable text content.");
            }
            byte[] bodyBytes = bodyStream.readNBytes(MAX_BODY_BYTES);
            String contentType = response.headers().firstValue("content-type").orElse("");
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            String text;

            if (contentType.contains("text/html") || contentType.isEmpty()) {
                text = htmlToText(body);
            } else {
                text = body;
            }

            if (text.isBlank()) {
                return ToolResponse.success("The page at " + sanitizeUrl(url) + " returned no readable text content.");
            }

            if (text.length() > maxChars) {
                text = truncateAtSentence(text, maxChars) + "\n[content truncated at " + maxChars + " chars]";
            }

            if (auditService != null) {
                auditService.record(
                        ctx.sessionId(),
                        ctx.userId(),
                        ctx.agentRole(),
                        SecurityEventType.TOOL_EXECUTED,
                        "Web fetch: " + sanitizeUrl(url));
            }

            return ToolResponse.success("Content from " + sanitizeUrl(url) + ":\n\n" + text);
        }
    }

    /** Returns an error message if the host resolves to a private/internal IP; null otherwise. */
    static String rejectPrivateHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "Invalid URL: missing host";
            }
            if (OutboundHttpPolicyService.isBlockedLiteralAddress(host)) {
                return "Access to private/internal network hosts is not allowed: " + host;
            }

            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress addr : addrs) {
                if (addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress()
                        || addr.isAnyLocalAddress()
                        || addr.isMulticastAddress()
                        || OutboundHttpPolicyService.isBlockedLiteralAddress(addr.getHostAddress())) {
                    return "Access to private/internal network hosts is not allowed: " + host;
                }
            }
        } catch (IllegalArgumentException e) {
            return "Invalid URL: " + e.getMessage();
        } catch (Exception _) {
            // DNS failure — the real request will fail anyway
        }
        return null;
    }
}
