package com.arcclaw.core.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.ToolOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Component A6 — SerperWebSearchTool.
 * Allows agents to search the web using the Serper (Google Search) API.
 */
@ApplicationScoped
public class SerperWebSearchTool implements AgentTool {

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

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
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
            .addProperty("query", JsonStringSchema.builder()
                .description("The search query to send to Serper.")
                .build())
            .addProperty("count", JsonIntegerSchema.builder()
                // Concrete examples force small models to stay on-type
                .description("Number of organic results to return. " +
                            "MUST be a JSON integer — correct: 5, wrong: \"5\". " +
                            "Allowed range: 1-10. Omit to use the default of 5.")
                .build())
            // Use 2-arg version to avoid potential MethodNotFound error (Fix I1)
            .addEnumProperty("type", List.of("search", "news"))
            .required("query")  // keep count optional so model can omit it safely
            .build();
    }

    private String resolveEndpoint(String type) {
        return "news".equals(type)
            ? "https://google.serper.dev/news"
            : "https://google.serper.dev/search";
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        String userId;
        String sessionId;
        String agentRole;

        try {
            // Step 1 — Extract and validate tenant context
            // NS2: Fail fast on missing context
            userId    = WorkspaceContext.requireUserId();
            sessionId = WorkspaceContext.requireSessionId();
            agentRole = WorkspaceContext.requireAgentRole();
        } catch (IllegalStateException e) {
            // NS2 backward compatibility: convert missing context into 403 ToolResponse
            if (e.getMessage() != null && e.getMessage().contains("unbound")) {
                return ToolResponse.failure(403, "Security violation: tenant context not bound.");
            }
            throw e;
        }
 
        String query = (String) params.get("query");
        if (query == null || query.isBlank()) {
            return ToolResponse.failure(400, "query parameter is required.");
        }

        if (!isEnabled()) {
            return ToolResponse.failure(503, "Serper Search is not configured. Set ARCCLAW_TOOLS_SERPER_API_KEY.");
        }

        int count = 5;
        if (params.containsKey("count")) {
            Object cObj = params.get("count");
            if (cObj instanceof Number n) {
                count = Math.min(10, Math.max(1, n.intValue()));
            }
        }

        String type = "search";
        if (params.containsKey("type")) {
            String t = (String) params.get("type");
            if ("news".equals(t)) {
                type = "news";
            }
        }

        try {
            ObjectNode bodyNode = objectMapper.createObjectNode();
            bodyNode.put("q", query);
            bodyNode.put("num", count);

            String requestBody = objectMapper.writeValueAsString(bodyNode);
            // Path-based routing for search type (Fix C2)
            URI endpoint = URI.create(resolveEndpoint(type));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(endpoint)
                .header("Content-Type", "application/json")
                .header("X-API-KEY", getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(15))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                 return ToolResponse.failure(response.statusCode(), "Serper Search API failed: " + response.body());
            }

            // Audit successful search — NS2: Fail fast on missing role
            auditService.record(sessionId, userId, agentRole, 
                SecurityEventType.TOOL_EXECUTED, "Serper search: " + query);

            JsonNode root = objectMapper.readTree(response.body());
            StringBuilder sb = new StringBuilder();

            // A. answerBox
            if (root.has("answerBox")) {
                JsonNode ab = root.get("answerBox");
                String answer = ab.has("answer") ? ab.get("answer").asText() :
                               ab.has("snippet") ? ab.get("snippet").asText() :
                               ab.has("snippetHighlighted") && ab.get("snippetHighlighted").isArray() && ab.get("snippetHighlighted").size() > 0 ?
                               ab.get("snippetHighlighted").get(0).asText() : null;
                if (answer != null) {
                    sb.append("## Direct Answer\n").append(answer).append("\n\n");
                }
            }

            // B. knowledgeGraph
            if (root.has("knowledgeGraph")) {
                JsonNode kg = root.get("knowledgeGraph");
                String kgTitle = kg.path("title").asText(null);
                if (kgTitle != null) {
                    sb.append("## Knowledge Graph\n");
                    sb.append("**").append(kgTitle).append("**");
                    if (kg.has("type")) sb.append(" (").append(kg.get("type").asText()).append(")");
                    sb.append("\n");
                    if (kg.has("description")) sb.append(kg.get("description").asText()).append("\n");
                    sb.append("\n");
                }
            }

            // C. organic
            JsonNode organic = root.path("organic");
            if (organic.isArray() && organic.size() > 0) {
                sb.append("## Web Results\n\n");
                for (JsonNode res : organic) {
                    sb.append("Title: ").append(res.path("title").asText("Untitled")).append("\n");
                    sb.append("URL:   ").append(res.path("link").asText("Unknown URL")).append("\n");
                    sb.append("Snippet: ").append(res.path("snippet").asText("No snippet available.")).append("\n");
                    if (res.has("date")) {
                        sb.append("Date: ").append(res.get("date").asText()).append("\n");
                    }
                    sb.append("\n");
                }
            }

            // D. peopleAlsoAsk
            JsonNode paa = root.path("peopleAlsoAsk");
            if (paa.isArray() && paa.size() >= 2) {
                sb.append("## People Also Ask\n");
                for (int i = 0; i < Math.min(3, paa.size()); i++) {
                    JsonNode p = paa.get(i);
                    sb.append("- ").append(p.path("question").asText()).append(": ")
                      .append(p.path("snippet").asText()).append("\n");
                }
                sb.append("\n");
            }

            String fullResult = sb.toString().trim();
            if (fullResult.length() < 50) { 
                 return ToolResponse.success("No relevant web or news results were found for the query: '" + query + 
                     "'. This might be due to a very specific query, current events not indexed, or API rate limitations.");
            }

            // Response truncation (at 4096 chars)
            if (fullResult.length() > 4096) {
                int lastNewline = fullResult.lastIndexOf("\n\n", 4096);
                fullResult = (lastNewline > 0 ? fullResult.substring(0, lastNewline) : fullResult.substring(0, 4096))
                             + "\n[results truncated]";
            }

            return ToolResponse.success(fullResult);

        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return ToolResponse.failure(502, "Serper search request failed: " + e.getMessage());
        }
    }
}
