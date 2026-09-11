package com.amsha.tools.builtin;

import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.amsha.common.util.LogUtil;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.ToolOperation;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolMetadata;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.arc.Unremovable;

/**
 * Component A6 - SerperWebSearchTool.
 * Allows agents to search the web using the Serper (Google Search) API.
 */
@ApplicationScoped
@Unremovable
@ToolMetadata(
        capabilityId = "serper_web_search",
        description = "Search the web for real-time Google results using the Serper API",
        defaultRoles = {"researcher", "synthesizer"})
public class SerperWebSearchTool implements AgentTool {

    public static final String TYPE_SEARCH = "search";
    public static final String TYPE_NEWS = "news";
    private static final String SERPER_SEARCH_URL = "https://google.serper.dev/search";
    private static final String SERPER_NEWS_URL = "https://google.serper.dev/news";

    private static final String PARAM_QUERY = "query";
    private static final String PARAM_COUNT = "count";
    private static final String PARAM_SNIPPET = "snippet";
    private static final String PARAM_SNIPPET_HIGHLIGHTED = "snippetHighlighted";

    @ConfigProperty(name = "amsha.tools.serper.api-key", defaultValue = "none")
    String configApiKey;

    @Inject
    SecretVault vault;

    private String getApiKey() {
        return vault.retrieveSystemKey("AMSHA_TOOLS_SERPER_API_KEY")
                .map(vk -> {
                    try (vk) {
                        return new String(vk.get(), StandardCharsets.UTF_8);
                    }
                })
                .orElse(configApiKey);
    }

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AuditService auditService;

    @Inject
    Logger log;

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
        throw new IllegalStateException("GovernedTransportService is required for Serper search egress");
    }

    @Override
    public String name() {
        return ToolCapability.WEB_SEARCH_SERPER.toolName();
    }

    @Override
    public String description() {
        return ToolCapability.WEB_SEARCH_SERPER.description();
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
        // Invariant: Use existence check instead of decryption during startup/init.
        if (vault.systemKeyExists("AMSHA_TOOLS_SERPER_API_KEY")) {
            return true;
        }
        return configApiKey != null && !configApiKey.isBlank() && !"none".equalsIgnoreCase(configApiKey);
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addProperty(
                        PARAM_QUERY,
                        JsonStringSchema.builder()
                                .description("The search query to send to Serper.")
                                .build())
                .addProperty(
                        PARAM_COUNT,
                        JsonIntegerSchema.builder()
                                .description(
                                        "Number of organic results to return (integer, 1-10). Default is 5. "
                                                + "Set this parameter to the specific number if the user asks for 'top N' results.")
                                .build())
                .addProperty(
                        "type",
                        JsonStringSchema.builder()
                                .description(
                                        "Optional result type: 'search' (default) for web results, or 'news' for latest news articles.")
                                .build())
                .required(PARAM_QUERY)
                .build();
    }

    @Override
    public String validateParameters(Map<String, Object> params) {
        if (params == null || !params.containsKey(PARAM_QUERY)) {
            return "Parameter 'query' is required for serper_web_search.";
        }
        Object queryObj = params.get(PARAM_QUERY);
        if (!(queryObj instanceof String q) || q.isBlank()) {
            return "Parameter 'query' is required for serper_web_search.";
        }
        return null;
    }

    private String resolveEndpoint(String type) {
        return TYPE_NEWS.equals(type) ? SERPER_NEWS_URL : SERPER_SEARCH_URL;
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String userId;
        String sessionId;
        String agentRole;

        userId = ctx.userId();
        sessionId = ctx.sessionId();
        agentRole = ctx.agentRole();

        if (userId == null || userId.isBlank() || sessionId == null || agentRole == null) {
            return ToolResponse.failure(403, "Security violation: tenant context not bound.");
        }

        String query = (String) params.get(PARAM_QUERY);
        if (query == null || query.isBlank()) {
            return ToolResponse.failure(400, "query parameter is required.");
        }

        if (!isEnabled()) {
            return ToolResponse.failure(503, "Serper Search is not configured. Set AMSHA_TOOLS_SERPER_API_KEY.");
        }

        int count = parseCount(params);
        String type = parseType(params);

        try {
            HttpResponse<String> response = fetchSerperResponse(query, count, type, userId);
            if (response.statusCode() != 200) {
                return ToolResponse.failure(response.statusCode(), "Serper Search API failed: " + response.body());
            }

            auditService.record(
                    sessionId, userId, agentRole, SecurityEventType.TOOL_EXECUTED, "Serper search: " + query);
            return parseSearchResponse(response.body(), query);
        } catch (OutboundHttpException e) {
            int code =
                    switch (e.category()) {
                        case POLICY_DENIED, DNS_REBINDING, REDIRECT_DENIED, TLS_TRUST -> 403;
                        case HITL_REQUIRED -> 409;
                        case TIMEOUT -> 504;
                        default -> 502;
                    };
            return ToolResponse.failure(code, "Serper search blocked: " + e.getMessage());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return ToolResponse.failure(502, "Serper search request failed: " + e.getMessage());
        }
    }

    private int parseCount(Map<String, Object> params) {
        if (!params.containsKey(PARAM_COUNT)) return 5;
        Object cObj = params.get(PARAM_COUNT);
        if (cObj instanceof Number n) {
            return Math.min(10, Math.max(1, n.intValue()));
        } else if (cObj instanceof String s) {
            try {
                return Math.min(10, Math.max(1, Integer.parseInt(s)));
            } catch (NumberFormatException e) {
                log.debugf("Failed to parse count parameter: %s", LogUtil.rootMessage(e));
            }
        }
        return 5;
    }

    private String parseType(Map<String, Object> params) {
        if (params.containsKey("type") && TYPE_NEWS.equals(params.get("type"))) {
            return TYPE_NEWS;
        }
        return TYPE_SEARCH;
    }

    private HttpResponse<String> fetchSerperResponse(String query, int count, String type, String userId)
            throws Exception {
        ObjectNode bodyNode = objectMapper.createObjectNode();
        bodyNode.put("q", query);
        bodyNode.put("num", count);

        String requestBody = objectMapper.writeValueAsString(bodyNode);
        URI endpoint = URI.create(resolveEndpoint(type));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpoint)
                .header("Content-Type", "application/json")
                .header("X-API-KEY", getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(15))
                .build();

        // HITL boundary: ToolInvocationGuard owns approval for NETWORK_OUTBOUND tools and always
        // pauses before execution, so transport-level preApproved is false. WEB_SEARCH is
        // intentionally non-HITL at transport to avoid a double prompt. Connection is null by
        // design: web search is connection-agnostic (default SSRF/TLS/DNS-rebinding policy).
        return resolveGovernedTransport()
                .send(
                        endpoint.toString(),
                        OutboundPurpose.WEB_SEARCH,
                        null,
                        userId,
                        false,
                        request,
                        HttpResponse.BodyHandlers.ofString());
    }

    private ToolResponse parseSearchResponse(String responseBody, String query) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        StringBuilder sb = new StringBuilder();

        if (root.has("answerBox")) {
            JsonNode ab = root.get("answerBox");
            String answer = ab.has("answer")
                    ? ab.get("answer").asText()
                    : ab.has(PARAM_SNIPPET)
                            ? ab.get(PARAM_SNIPPET).asText()
                            : ab.has(PARAM_SNIPPET_HIGHLIGHTED)
                                            && ab.get(PARAM_SNIPPET_HIGHLIGHTED).isArray()
                                            && ab.get(PARAM_SNIPPET_HIGHLIGHTED).size() > 0
                                    ? ab.get(PARAM_SNIPPET_HIGHLIGHTED).get(0).asText()
                                    : null;
            if (answer != null) {
                sb.append("## Direct Answer\n").append(answer).append("\n\n");
            }
        }

        if (root.has("knowledgeGraph")) {
            JsonNode kg = root.get("knowledgeGraph");
            String kgTitle = kg.path("title").asText(null);
            if (kgTitle != null) {
                sb.append("## Knowledge Graph\n");
                sb.append("**").append(kgTitle).append("**");
                if (kg.has("type"))
                    sb.append(" (").append(kg.get("type").asText()).append(")");
                sb.append("\n");
                if (kg.has("description"))
                    sb.append(kg.get("description").asText()).append("\n");
                sb.append("\n");
            }
        }

        JsonNode results = root.has("news") ? root.path("news") : root.path("organic");
        if (results.isArray() && results.size() > 0) {
            sb.append(root.has("news") ? "## News Results\n\n" : "## Web Results\n\n");
            for (JsonNode res : results) {
                sb.append("Title: ")
                        .append(res.path("title").asText("Untitled"))
                        .append("\n");
                sb.append("URL:   ")
                        .append(res.path("link").asText("Unknown URL"))
                        .append("\n");
                sb.append("Snippet: ")
                        .append(res.path(PARAM_SNIPPET).asText("No snippet available."))
                        .append("\n");
                if (res.has("date")) {
                    sb.append("Date: ").append(res.get("date").asText()).append("\n");
                }
                sb.append("\n");
            }
        }

        JsonNode paa = root.path("peopleAlsoAsk");
        if (paa.isArray() && paa.size() >= 2) {
            sb.append("## People Also Ask\n");
            for (int i = 0; i < Math.min(3, paa.size()); i++) {
                JsonNode p = paa.get(i);
                sb.append("- ")
                        .append(p.path("question").asText())
                        .append(": ")
                        .append(p.path(PARAM_SNIPPET).asText())
                        .append("\n");
            }
            sb.append("\n");
        }

        String fullResult = sb.toString().trim();
        if (fullResult.length() < 50) {
            return ToolResponse.success("SEARCH_EXHAUSTED: No relevant web or news results were found for the query: '"
                    + query + "'. Do not retry with similar queries. FINISH with an apology.");
        }

        if (fullResult.length() > 4096) {
            int lastNewline = fullResult.lastIndexOf("\n\n", 4096);
            fullResult = (lastNewline > 0 ? fullResult.substring(0, lastNewline) : fullResult.substring(0, 4096))
                    + "\n[results truncated]";
        }

        return ToolResponse.success(fullResult);
    }
}
