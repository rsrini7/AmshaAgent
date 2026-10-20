package com.amsha.tools.builtin;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.amsha.common.http.HttpProtocol;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.ToolOperation;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolMetadata;
import com.amsha.tool.api.ToolNames;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.arc.Unremovable;

/**
 * Component A5 - BraveWebSearchTool.
 * Allows agents to search the web using the Brave Search API.
 */
@ApplicationScoped
@Unremovable
@ToolMetadata(
        capabilityId = ToolNames.WEB_SEARCH_BRAVE,
        description = "Search the web for real-time information using the Brave API",
        defaultRoles = {AgentRoleEnum.RESEARCHER_ID, AgentRoleEnum.SYNTHESIZER_ID})
public class BraveWebSearchTool implements AgentTool {

    @ConfigProperty(name = "amsha.tools.brave.api-key", defaultValue = "none")
    String configApiKey;

    @Inject
    SecretVault vault;

    private String getApiKey() {
        return vault.retrieveSystemKey(VaultConstants.TOOLS_BRAVE_API_KEY)
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

    private static final String BRAVE_API_URL = "https://api.search.brave.com/res/v1/web/search";

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
        throw new IllegalStateException("GovernedTransportService is required for Brave search egress");
    }

    @Override
    public String name() {
        return ToolCapability.WEB_SEARCH_BRAVE.toolName();
    }

    @Override
    public String description() {
        return ToolCapability.WEB_SEARCH_BRAVE.description();
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
        // This prevents boot-time crashes in security tests where master keys mismatch.
        if (vault.systemKeyExists(VaultConstants.TOOLS_BRAVE_API_KEY)) {
            return true;
        }
        return configApiKey != null && !configApiKey.isBlank() && !"none".equalsIgnoreCase(configApiKey);
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("query", "The search query to send to Brave.")
                .addIntegerProperty("count", "Number of results (1-20). Default: 5.") // Added (Fix I2)
                .required("query")
                .build();
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String userId = ctx.userId();
        String sessionId = ctx.sessionId();
        String agentRole = ctx.agentRole();

        String query = (String) params.get("query");
        if (userId == null || userId.isBlank()) {
            return ToolResponse.failure(403, "Forbidden: userId is required for Brave web search.");
        }
        if (query == null || query.isBlank()) {
            return ToolResponse.failure(400, "query parameter is required.");
        }

        if (!isEnabled()) {
            return ToolResponse.failure(503, "Brave Search is not configured. (amsha.tools.brave.api-key is missing)");
        }

        // Support count parameter (Fix I2)
        int count = 5;
        if (params.containsKey("count")) {
            Object cObj = params.get("count");
            if (cObj instanceof Number n) {
                count = Math.min(20, Math.max(1, n.intValue()));
            }
        }

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            URI uri = URI.create(BRAVE_API_URL + "?q=" + encodedQuery + "&count=" + count);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header(HttpProtocol.HEADER_ACCEPT, HttpProtocol.MEDIA_TYPE_JSON)
                    .header("X-Subscription-Token", getApiKey())
                    .GET()
                    .build();

            // HITL boundary: ToolInvocationGuard owns approval for NETWORK_OUTBOUND tools and always
            // pauses before execution, so transport-level preApproved is false. WEB_SEARCH is
            // intentionally non-HITL at transport (OutboundHttpPolicyService) to avoid a double prompt;
            // the tool must never claim pre-approval. Connection is null by design: web search is
            // connection-agnostic and uses the default policy (SSRF/TLS/DNS-rebinding validation).
            // Per-connection proxy/pinning applies only when a connection is supplied.
            HttpResponse<String> response = resolveGovernedTransport()
                    .send(
                            uri.toString(),
                            OutboundPurpose.WEB_SEARCH,
                            null,
                            userId,
                            false,
                            request,
                            HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return ToolResponse.failure(response.statusCode(), "Brave Search API failed: " + response.body());
            }

            // Always audit even if zero results (Fix I3)
            auditService.record(sessionId, userId, agentRole, SecurityEventType.TOOL_EXECUTED, "Web search: " + query);

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.path("web").path("results");

            StringBuilder sb = new StringBuilder();
            sb.append("Web Search results for: \"").append(query).append("\"\n\n");

            if (results.isArray()) {
                for (int i = 0; i < results.size(); i++) {
                    JsonNode result = results.get(i);
                    sb.append("Title: ")
                            .append(result.path("title").asText("Untitled"))
                            .append("\n");
                    sb.append("URL:   ")
                            .append(result.path("url").asText("Unknown URL"))
                            .append("\n");
                    sb.append("Description: ")
                            .append(result.path("description").asText("No snippet available."))
                            .append("\n\n");
                }
            }

            String fullResult = sb.toString().trim();
            if (fullResult.length() < 50) {
                return ToolResponse.success("No results found for your query.");
            }

            // Implement response truncation (Fix I4)
            if (fullResult.length() > 4096) {
                int lastNewline = fullResult.lastIndexOf("\n\n", 4096);
                fullResult = (lastNewline > 0 ? fullResult.substring(0, lastNewline) : fullResult.substring(0, 4096))
                        + "\n[results truncated]";
            }

            return ToolResponse.success(fullResult);

        } catch (OutboundHttpException e) {
            int code =
                    switch (e.category()) {
                        case POLICY_DENIED, DNS_REBINDING, REDIRECT_DENIED, TLS_TRUST -> 403;
                        case HITL_REQUIRED -> 409;
                        case TIMEOUT -> 504;
                        default -> 502;
                    };
            return ToolResponse.failure(code, "Web search blocked: " + e.getMessage());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return ToolResponse.failure(502, "Web search request failed: " + e.getMessage());
        }
    }
}
