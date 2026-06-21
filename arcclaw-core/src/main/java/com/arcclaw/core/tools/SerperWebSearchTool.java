package com.arcclaw.core.tools;

import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.ToolOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Component A6 - SerperWebSearchTool.
 * Allows agents to search the web using the Serper (Google Search) API.
 */
@ApplicationScoped
@ToolMetadata(
        capabilityId = "serper_web_search",
        description = "Search the web for real-time Google results using the Serper API",
        defaultRoles = {"researcher", "synthesizer"})
public class SerperWebSearchTool implements AgentTool {

    private static final String PARAM_QUERY = "query";
    private static final String PARAM_COUNT = "count";
    private static final String PARAM_SNIPPET = "snippet";
    private static final String PARAM_SNIPPET_HIGHLIGHTED = "snippetHighlighted";

    @ConfigProperty(name = "arcclaw.tools.serper.api-key", defaultValue = "none")
    String configApiKey;

    @Inject
    SecretVault vault;

    private String getApiKey() {
        return vault.retrieveSystemKey("ARCCLAW_TOOLS_SERPER_API_KEY")
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

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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
        if (vault.systemKeyExists("ARCCLAW_TOOLS_SERPER_API_KEY")) {
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

    private String resolveEndpoint(String type) {
        return "news".equals(type) ? "https://google.serper.dev/news" : "https://google.serper.dev/search";
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
            return ToolResponse.failure(503, "Serper Search is not configured. Set ARCCLAW_TOOLS_SERPER_API_KEY.");
        }

        int count = parseCount(params);
        String type = parseType(params);

        try {
            HttpResponse<String> response = fetchSerperResponse(query, count, type);
            if (response.statusCode() != 200) {
                return ToolResponse.failure(response.statusCode(), "Serper Search API failed: " + response.body());
            }

            auditService.record(
                    sessionId, userId, agentRole, SecurityEventType.TOOL_EXECUTED, "Serper search: " + query);
            return parseSearchResponse(response.body(), query);
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
        if (params.containsKey("type") && "news".equals(params.get("type"))) {
            return "news";
        }
        return "search";
    }

    private HttpResponse<String> fetchSerperResponse(String query, int count, String type) throws Exception {
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

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
