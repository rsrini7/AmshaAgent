package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.routing.provider.ConnectionValidationRequest;
import com.amsha.core.routing.provider.LlmProviderOnboardingAdapter;
import com.amsha.core.routing.provider.ProviderValidationResult;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultKey;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Handles secret scrubbing, vault reference resolution, and connection validation for onboarding
 * selections. Extracted from {@link OnboardingCoordinator} to keep that class under the PMD
 * ExcessiveClassLength threshold and reduce the cognitive complexity of {@code processAndScrubSecrets}.
 */
@ApplicationScoped
public class OnboardingSelectionScrubber {

    private static final Logger log = Logger.getLogger(OnboardingSelectionScrubber.class);

    @Inject
    SecretVault vault;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    LlmProviderOnboardingAdapter providerAdapter;

    /**
     * Scrubs sensitive values (API keys, MCP auth headers) from selection payloads and stores them
     * in the vault. Returns a new list with vault references substituted for plaintext secrets.
     */
    @SuppressWarnings("unchecked")
    public List<String> processAndScrubSecrets(String ownerId, List<String> selections) {
        if (selections == null || selections.isEmpty()) {
            return selections;
        }
        List<String> scrubbed = new ArrayList<>();
        for (String selection : selections) {
            try {
                Map<String, Object> payload = objectMapper.readValue(selection, Map.class);
                boolean modified = false;

                modified |= scrubConnectionApiKeys(payload, ownerId);
                modified |= scrubMcpHeaders(payload, ownerId);

                if (modified) {
                    scrubbed.add(objectMapper.writeValueAsString(payload));
                } else {
                    scrubbed.add(selection);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Onboarding selections must be valid JSON", e);
            }
        }
        return scrubbed;
    }

    /**
     * Cleans up staged secrets that were not adopted by a committed connection. Called on discard,
     * expiry, denial, terminal failure, or completion. Idempotent and durable-retryable.
     */
    @SuppressWarnings("unchecked")
    public void cleanupStagedSecrets(OnboardingDraftDto draft) {
        if (draft.selectedNonSecretIds() == null || draft.selectedNonSecretIds().isEmpty()) return;
        for (String sel : draft.selectedNonSecretIds()) {
            try {
                Map<String, Object> payload = objectMapper.readValue(sel, Map.class);
                cleanupConnectionSecrets(payload, draft);
                cleanupMcpHeaderSecrets(payload, draft);
            } catch (Exception _) {
                // not a JSON map; skip
            }
        }
    }

    /**
     * Resolves vault references in an extension payload. For MCP payloads, replaces
     * {@code headerVaultRefs} with resolved {@code headers} by reading from the vault.
     */
    @SuppressWarnings("unchecked")
    public String resolveVaultReferences(String ownerId, String payload) {
        try {
            Map<String, Object> map = objectMapper.readValue(payload, Map.class);
            Map<String, String> headerVaultRefs = (Map<String, String>) map.get("headerVaultRefs");
            if (headerVaultRefs == null || headerVaultRefs.isEmpty()) return payload;
            Map<String, String> resolvedHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : headerVaultRefs.entrySet()) {
                String headerName = entry.getKey();
                String vaultRef = entry.getValue();
                Optional<VaultKey> key = vault.get(vaultRef, ownerId);
                if (key.isPresent()) {
                    try (VaultKey k = key.get()) {
                        resolvedHeaders.put(headerName, new String(k.get(), StandardCharsets.UTF_8));
                    }
                } else {
                    throw new IllegalStateException("A staged MCP authentication header could not be resolved");
                }
            }
            map.remove("headerVaultRefs");
            map.put("headers", resolvedHeaders);
            return objectMapper.writeValueAsString(map);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to resolve staged MCP authentication headers", e);
        }
    }

    /**
     * Extracts the sub-payload for a specific extension from the combined selections list.
     */
    public String extractExtensionPayload(List<String> selections, String extensionId) {
        if (selections == null || selections.isEmpty()) return "{}";
        for (String sel : selections) {
            try {
                Map<String, Object> map = objectMapper.readValue(sel, Map.class);
                if (map.containsKey(extensionId)) {
                    return objectMapper.writeValueAsString(map.get(extensionId));
                }
            } catch (Exception _) {
                // not a JSON map; continue
            }
        }
        return "{}";
    }

    /**
     * Tiered provider validation for the connections step. Extracts connection selections from the
     * payload and validates each through the provider adapter. Returns a safe error string on
     * failure, or null when all connections are valid.
     */
    @SuppressWarnings("unchecked")
    public String validateConnections(List<String> selections) {
        if (providerAdapter == null) return null;
        List<String> errors = new ArrayList<>();
        for (String sel : selections) {
            try {
                Map<String, Object> payload = objectMapper.readValue(sel, Map.class);
                List<Map<String, Object>> connections = (List<Map<String, Object>>) payload.get("connections");
                if (connections == null) continue;
                for (Map<String, Object> conn : connections) {
                    String provider = String.valueOf(conn.getOrDefault("provider", ""));
                    String model = String.valueOf(conn.getOrDefault("modelId", ""));
                    String baseUrl = String.valueOf(conn.getOrDefault("customBaseUrl", ""));
                    boolean hasKey = conn.containsKey("apiKeyVaultRef")
                            || (conn.get("apiKey") != null
                                    && !String.valueOf(conn.get("apiKey")).isBlank());
                    List<String> allowedHosts = List.of();
                    Object egress = conn.get("allowedEgressHosts");
                    if (egress instanceof List<?> list) {
                        allowedHosts = list.stream()
                                .filter(String.class::isInstance)
                                .map(String.class::cast)
                                .toList();
                    }
                    ProviderValidationResult result = providerAdapter.validate(
                            new ConnectionValidationRequest(provider, model, baseUrl, hasKey, allowedHosts));
                    if (!result.eligible()) {
                        errors.addAll(result.errors());
                    }
                }
            } catch (Exception _) {
                // not a JSON map; skip
            }
        }
        return errors.isEmpty() ? null : String.join("; ", errors);
    }

    // ---- internal helpers ----

    @SuppressWarnings("unchecked")
    private boolean scrubConnectionApiKeys(Map<String, Object> payload, String ownerId) {
        List<Map<String, Object>> connections = (List<Map<String, Object>>) payload.get("connections");
        if (connections == null) return false;
        boolean modified = false;
        for (Map<String, Object> conn : connections) {
            String apiKey = (String) conn.get("apiKey");
            String vaultRef = (String) conn.get("apiKeyVaultRef");
            if (apiKey != null && !apiKey.isBlank()) {
                if (vaultRef == null || vaultRef.isBlank() || vaultRef.startsWith("staged-ref-")) {
                    vaultRef = "vault-key-"
                            + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    conn.put("apiKeyVaultRef", vaultRef);
                }
                vault.store(vaultRef, apiKey.getBytes(StandardCharsets.UTF_8), ownerId);
                conn.remove("apiKey");
                modified = true;
            } else if (conn.containsKey("apiKey")) {
                conn.remove("apiKey");
                modified = true;
            }
        }
        return modified;
    }

    @SuppressWarnings("unchecked")
    private boolean scrubMcpHeaders(Map<String, Object> payload, String ownerId) {
        Map<String, Object> mcp = (Map<String, Object>) payload.get("mcp");
        if (mcp == null) return false;
        Map<String, String> headers = (Map<String, String>) mcp.get("headers");
        if (headers == null || headers.isEmpty()) return false;
        Map<String, String> headerVaultRefs = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String headerValue = entry.getValue();
            if (headerValue != null && !headerValue.isBlank()) {
                String ref = "mcp-header-"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                vault.store(ref, headerValue.getBytes(StandardCharsets.UTF_8), ownerId);
                headerVaultRefs.put(entry.getKey(), ref);
            }
        }
        if (!headerVaultRefs.isEmpty()) {
            mcp.put("headerVaultRefs", headerVaultRefs);
            mcp.remove("headers");
            return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void cleanupConnectionSecrets(Map<String, Object> payload, OnboardingDraftDto draft) {
        List<Map<String, Object>> connections = (List<Map<String, Object>>) payload.get("connections");
        if (connections == null) return;
        for (Map<String, Object> conn : connections) {
            String vaultRef = (String) conn.get("apiKeyVaultRef");
            if (vaultRef != null && vaultRef.startsWith("vault-key-")) {
                try {
                    vault.remove(vaultRef, draft.ownerId());
                    log.debugf("[ONBOARDING] Cleaned staged secret %s for draft %s", vaultRef, draft.draftId());
                } catch (Exception e) {
                    log.warnf("[ONBOARDING] Failed to clean staged secret %s: %s", vaultRef, e.getMessage());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void cleanupMcpHeaderSecrets(Map<String, Object> payload, OnboardingDraftDto draft) {
        Map<String, Object> mcp = (Map<String, Object>) payload.get("mcp");
        if (mcp == null) return;
        Map<String, String> headerVaultRefs = (Map<String, String>) mcp.get("headerVaultRefs");
        if (headerVaultRefs == null) return;
        for (String ref : headerVaultRefs.values()) {
            try {
                vault.remove(ref, draft.ownerId());
                log.debugf("[ONBOARDING] Cleaned staged MCP header %s for draft %s", ref, draft.draftId());
            } catch (Exception e) {
                log.warnf("[ONBOARDING] Failed to clean staged MCP header %s: %s", ref, e.getMessage());
            }
        }
    }
}
