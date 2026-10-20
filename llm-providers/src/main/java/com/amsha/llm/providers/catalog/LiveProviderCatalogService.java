package com.amsha.llm.providers.catalog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.amsha.common.http.HttpProtocol;
import com.amsha.common.util.LogUtil;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.ModelEntry;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.http.GovernedTransportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * Service for live model discovery across supported LLM providers.
 * All outbound probes are strictly governed through {@link GovernedTransportService}
 * under {@link OutboundPurpose#PROVIDER_MODEL_DISCOVERY} with bounded timeouts.
 * Probed models are cached with a 5-minute TTL and fall back gracefully to the static
 * catalog if endpoints are unreachable, unconfigured, or error out.
 */
@ApplicationScoped
@Unremovable
public class LiveProviderCatalogService {

    private static final Logger LOG = Logger.getLogger(LiveProviderCatalogService.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);

    @Inject
    Instance<GovernedTransportService> governedTransportInstance;

    @Inject
    Instance<AuditService> auditServiceInstance;

    @Inject
    Instance<SecretVault> vaultInstance;

    @Inject
    Instance<ObjectMapper> objectMapperInstance;

    GovernedTransportService governedTransport;
    AuditService auditService;
    SecretVault vault;
    ObjectMapper objectMapper;

    private final Cache<String, List<ModelEntry>> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    GovernedTransportService resolveGovernedTransport() {
        if (governedTransport != null) {
            return governedTransport;
        }
        if (governedTransportInstance != null && governedTransportInstance.isResolvable()) {
            return governedTransportInstance.get();
        }
        return null;
    }

    AuditService resolveAuditService() {
        if (auditService != null) {
            return auditService;
        }
        if (auditServiceInstance != null && auditServiceInstance.isResolvable()) {
            return auditServiceInstance.get();
        }
        return null;
    }

    SecretVault resolveVault() {
        if (vault != null) {
            return vault;
        }
        if (vaultInstance != null && vaultInstance.isResolvable()) {
            return vaultInstance.get();
        }
        return null;
    }

    ObjectMapper resolveObjectMapper() {
        if (objectMapper != null) {
            return objectMapper;
        }
        if (objectMapperInstance != null && objectMapperInstance.isResolvable()) {
            return objectMapperInstance.get();
        }
        return new ObjectMapper();
    }

    /**
     * Discovers models for a given provider, querying live endpoints when feasible,
     * otherwise falling back to the supplied default models.
     */
    public List<ModelEntry> discoverModels(
            String providerId, String customBaseUrl, String apiKey, String actorUserId, List<ModelEntry> fallback) {
        if (providerId == null || providerId.isBlank()) {
            return fallback != null ? fallback : List.of();
        }
        String pid = providerId.toLowerCase(Locale.ROOT);
        String effBaseUrl = resolveBaseUrl(pid, customBaseUrl);
        if (effBaseUrl != null) {
            try {
                URI uri = URI.create(effBaseUrl);
                if (uri.getHost() != null && OutboundHttpPolicyService.isBlockedLiteralAddress(uri.getHost())) {
                    LOG.warnf("Custom base URL host is blocked: %s", uri.getHost());
                    return fallback != null ? fallback : List.of();
                }
            } catch (Exception _) {
                return fallback != null ? fallback : List.of();
            }
        }
        String effApiKey = resolveApiKey(pid, apiKey);
        String effectiveActor = (actorUserId != null && !actorUserId.isBlank()) ? actorUserId : "system";

        String cacheKey = pid + "|" + effBaseUrl + "|" + effectiveActor;
        List<ModelEntry> cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<ModelEntry> live = probeUpstream(pid, effBaseUrl, effApiKey, effectiveActor);
        if (live != null && !live.isEmpty()) {
            cache.put(cacheKey, live);
            return live;
        }

        return fallback != null ? fallback : List.of();
    }

    private List<ModelEntry> probeUpstream(String providerId, String baseUrl, String apiKey, String actorUserId) {
        GovernedTransportService transport = resolveGovernedTransport();
        if (transport == null) {
            LOG.debugf("GovernedTransportService not available; skipping live probe for %s", providerId);
            return List.of();
        }

        String probeUrl = buildProbeUrl(providerId, baseUrl);
        if (probeUrl == null || probeUrl.isBlank()) {
            return List.of();
        }

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(probeUrl))
                    .timeout(PROBE_TIMEOUT)
                    .GET();

            applyAuthHeaders(providerId, reqBuilder, apiKey);
            HttpRequest request = reqBuilder.build();

            String effectiveActor = (actorUserId != null && !actorUserId.isBlank()) ? actorUserId : "system";
            HttpResponse<String> response = transport.send(
                    probeUrl,
                    OutboundPurpose.PROVIDER_MODEL_DISCOVERY,
                    null,
                    effectiveActor,
                    true,
                    request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // Log the host only — probeUrl may embed user:pass@host credentials that must not reach logs.
                String probeHost;
                try {
                    probeHost = URI.create(probeUrl).getHost();
                } catch (Exception _) {
                    probeHost = "<unknown-host>";
                }
                LOG.warnf("Live model probe failed for %s (%s): HTTP %d", providerId, probeHost, response.statusCode());
                return List.of();
            }

            List<ModelEntry> models = parseModels(providerId, response.body());
            AuditService audit = resolveAuditService();
            if (audit != null && !models.isEmpty()) {
                audit.recordMandatory(
                        "live-catalog",
                        effectiveActor,
                        "system",
                        SecurityEventType.TOOL_EXECUTED,
                        "provider_model_discovery provider=" + providerId + " count=" + models.size());
            }
            return models;
        } catch (Exception e) {
            LOG.warnf("Error probing live models for %s: %s", providerId, LogUtil.rootMessage(e));
            return List.of();
        }
    }

    private String buildProbeUrl(String providerId, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String cleanUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        return switch (providerId) {
            case ModelDefaults.PROVIDER_OLLAMA -> cleanUrl + "/api/tags";
            case ModelDefaults.PROVIDER_ANTHROPIC, ModelDefaults.PROVIDER_CLAUDE_CODE ->
                cleanUrl.endsWith("/v1") ? cleanUrl + "/models" : cleanUrl + "/v1/models";
            case ModelDefaults.PROVIDER_GEMINI ->
                cleanUrl.endsWith("/v1beta") ? cleanUrl + "/models" : cleanUrl + "/v1beta/models";
            default -> cleanUrl.endsWith("/models") ? cleanUrl : cleanUrl + "/models";
        };
    }

    private void applyAuthHeaders(String providerId, HttpRequest.Builder builder, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        switch (providerId) {
            case ModelDefaults.PROVIDER_ANTHROPIC, ModelDefaults.PROVIDER_CLAUDE_CODE -> {
                builder.header("x-api-key", apiKey);
                builder.header("anthropic-version", "2023-06-01");
            }
            case ModelDefaults.PROVIDER_GEMINI -> {
                builder.header("x-goog-api-key", apiKey);
            }
            case ModelDefaults.PROVIDER_OLLAMA -> {
                // Ollama local endpoint typically requires no auth
            }
            default -> builder.header(HttpProtocol.HEADER_AUTHORIZATION, HttpProtocol.AUTH_SCHEME_BEARER + apiKey);
        }
    }

    private List<ModelEntry> parseModels(String providerId, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = resolveObjectMapper().readTree(responseBody);
            List<ModelEntry> result = new ArrayList<>();

            if (ModelDefaults.PROVIDER_OLLAMA.equals(providerId)) {
                parseOllamaModels(root, result);
            } else if (ModelDefaults.PROVIDER_GEMINI.equals(providerId)) {
                parseGeminiModels(root, result);
            } else {
                parseStandardModels(root, result);
            }

            return result.stream()
                    .distinct()
                    .sorted(Comparator.comparing(ModelEntry::id))
                    .toList();
        } catch (Exception e) {
            LOG.warnf("Failed to parse model discovery response for %s: %s", providerId, e.getMessage());
            return List.of();
        }
    }

    private void parseOllamaModels(JsonNode root, List<ModelEntry> result) {
        JsonNode modelsNode = root.path("models");
        if (modelsNode.isArray()) {
            for (JsonNode m : modelsNode) {
                String name = m.path("name").asText(m.path("model").asText(null));
                if (name != null && !name.isBlank()) {
                    result.add(new ModelEntry(name, name));
                }
            }
        }
    }

    private void parseGeminiModels(JsonNode root, List<ModelEntry> result) {
        JsonNode modelsNode = root.path("models");
        if (modelsNode.isArray()) {
            for (JsonNode m : modelsNode) {
                String rawName = m.path("name").asText(null);
                if (rawName != null && !rawName.isBlank()) {
                    String id = rawName.startsWith("models/") ? rawName.substring("models/".length()) : rawName;
                    String label = m.path("displayName").asText(id);
                    result.add(new ModelEntry(id, label));
                }
            }
        }
    }

    private void parseStandardModels(JsonNode root, List<ModelEntry> result) {
        JsonNode listNode = root.has("data") ? root.path("data") : root.path("models");
        if (listNode.isArray()) {
            for (JsonNode m : listNode) {
                String id = m.path("id").asText(m.path("name").asText(null));
                if (id != null && !id.isBlank()) {
                    String label =
                            m.path("display_name").asText(m.path("displayName").asText(id));
                    result.add(new ModelEntry(id, label));
                }
            }
        }
    }

    private String resolveBaseUrl(String providerId, String customBaseUrl) {
        if (customBaseUrl != null && !customBaseUrl.isBlank()) {
            return customBaseUrl.trim();
        }
        return ModelDefaults.defaultBaseUrlForProvider(providerId);
    }

    private String resolveApiKey(String providerId, String apiKey) {
        if (apiKey != null && !apiKey.isBlank() && !"none".equalsIgnoreCase(apiKey)) {
            return apiKey.trim();
        }
        SecretVault v = resolveVault();
        if (v == null) {
            return null;
        }
        String upper = providerId.toUpperCase(Locale.ROOT).replace('-', '_');
        return v.retrieveSystemKey("AMSHA_" + upper + "_API_KEY")
                .or(() -> v.retrieveSystemKey(upper + "_API_KEY"))
                .map(vk -> {
                    try (vk) {
                        return new String(vk.get(), StandardCharsets.UTF_8);
                    }
                })
                .orElse(null);
    }

    /** Clears the in-memory cache for testing or runtime refreshes. */
    public void clearCache() {
        cache.invalidateAll();
    }
}
