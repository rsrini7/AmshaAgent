package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Handles secret scrubbing and vault reference resolution for onboarding selections.
 * Extracted from {@link OnboardingCoordinator} to keep that class under the PMD
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
    OnboardingSelectionValidator validator;

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

                modified |= scrubMapSecrets(payload, ownerId);
                modified |= scrubConnectionApiKeys(payload, ownerId);
                modified |= scrubMcpHeaders(payload, ownerId);
                modified |= scrubMcpServerHeaders(payload, ownerId);
                modified |= scrubOptionalSections(payload, ownerId);

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
                Set<String> cleanedRefs = new HashSet<>();
                cleanupMapSecrets(payload, draft, cleanedRefs);
                cleanupConnectionSecrets(payload, draft, cleanedRefs);
                cleanupMcpHeaderSecrets(payload, draft, cleanedRefs);
                cleanupMcpServerHeaderSecrets(payload, draft, cleanedRefs);
                cleanupOptionalSecrets(payload, draft, cleanedRefs);
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
            boolean modified = resolveMcpHeaders(map, ownerId);
            Object nestedMcp = map.get("mcp");
            if (nestedMcp instanceof Map<?, ?> rawMcp) {
                modified |= resolveMcpHeaders((Map<String, Object>) rawMcp, ownerId);
            }
            return modified ? objectMapper.writeValueAsString(map) : payload;
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
     * Tiered provider validation for the connections step. Delegates to
     * {@link OnboardingSelectionValidator}.
     */
    public String validateConnections(List<String> selections) {
        return validator != null
                ? validator.validateConnections(selections)
                : "OnboardingSelectionValidator is unavailable";
    }

    // ---- internal helpers ----

    @SuppressWarnings("unchecked")
    private boolean scrubConnectionApiKeys(Map<String, Object> payload, String ownerId) {
        List<Map<String, Object>> connections = (List<Map<String, Object>>) payload.get("connections");
        if (connections == null) return false;
        boolean modified = false;
        for (Map<String, Object> conn : connections) {
            String apiKey = conn.get("apiKey") instanceof String s ? s : null;
            String vaultRef = conn.get("apiKeyVaultRef") instanceof String s ? s : null;
            if (apiKey != null && !apiKey.isBlank()) {
                if (vaultRef == null || vaultRef.isBlank() || vaultRef.startsWith("staged-ref-")) {
                    vaultRef = newVaultKeyRef();
                    conn.put("apiKeyVaultRef", vaultRef);
                }
                storeSecret(vaultRef, apiKey, ownerId);
                conn.remove("apiKey");
                modified = true;
            } else if (conn.containsKey("apiKey")) {
                conn.remove("apiKey");
                modified = true;
            }
            modified |= scrubMapSecrets(conn, ownerId);
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
                String ref = newMcpHeaderRef();
                storeSecret(ref, headerValue, ownerId);
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
    private boolean scrubMcpServerHeaders(Map<String, Object> payload, String ownerId) {
        Object rawMcp = payload.get("mcp");
        if (!(rawMcp instanceof Map<?, ?> rawMap)) return false;
        Map<String, Object> mcp = (Map<String, Object>) rawMap;
        Object rawServers = mcp.get("servers");
        if (!(rawServers instanceof List<?> servers)) return false;
        boolean modified = false;
        for (Object rawServer : servers) {
            if (rawServer instanceof Map<?, ?> rawServerMap) {
                Map<String, Object> srv = (Map<String, Object>) rawServerMap;
                modified |= scrubMcpHeaderMap(srv, ownerId);
                modified |= scrubMapSecrets(srv, ownerId);
            }
        }
        return modified;
    }

    private boolean scrubMcpHeaderMap(Map<String, Object> mcp, String ownerId) {
        Object rawHeaders = mcp.get("headers");
        if (!(rawHeaders instanceof Map<?, ?> headers) || headers.isEmpty()) return false;
        Map<String, String> headerVaultRefs = new LinkedHashMap<>();
        Object rawRefs = mcp.get("headerVaultRefs");
        if (rawRefs instanceof Map<?, ?> refs) {
            for (Map.Entry<?, ?> entry : refs.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String ref) {
                    headerVaultRefs.put(key, ref);
                }
            }
        }
        for (Map.Entry<?, ?> entry : headers.entrySet()) {
            if (entry.getKey() instanceof String headerName
                    && entry.getValue() instanceof String headerValue
                    && !headerValue.isBlank()) {
                String ref = newMcpHeaderRef();
                storeSecret(ref, headerValue, ownerId);
                headerVaultRefs.put(headerName, ref);
            }
        }
        if (headerVaultRefs.isEmpty()) return false;
        mcp.put("headerVaultRefs", headerVaultRefs);
        mcp.remove("headers");
        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean resolveMcpHeaders(Map<String, Object> payload, String ownerId) {
        boolean modified = resolveMcpHeaderMap(payload, ownerId);
        Object rawServers = payload.get("servers");
        if (rawServers instanceof List<?> servers) {
            for (Object rawServer : servers) {
                if (rawServer instanceof Map<?, ?> rawServerMap) {
                    modified |= resolveMcpHeaderMap((Map<String, Object>) rawServerMap, ownerId);
                }
            }
        }
        return modified;
    }

    private boolean resolveMcpHeaderMap(Map<String, Object> payload, String ownerId) {
        Object rawRefs = payload.get("headerVaultRefs");
        if (!(rawRefs instanceof Map<?, ?> refs) || refs.isEmpty()) return false;
        Map<String, String> resolvedHeaders = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : refs.entrySet()) {
            if (!(entry.getKey() instanceof String headerName)
                    || !(entry.getValue() instanceof String vaultRef)
                    || vaultRef.isBlank()) {
                throw new IllegalStateException("A staged MCP authentication header reference is invalid");
            }
            Optional<VaultKey> key = vault.get(vaultRef, ownerId);
            if (key.isEmpty()) {
                throw new IllegalStateException("A staged MCP authentication header could not be resolved");
            }
            try (VaultKey resolvedKey = key.get()) {
                resolvedHeaders.put(headerName, new String(resolvedKey.get(), StandardCharsets.UTF_8));
            }
        }
        payload.remove("headerVaultRefs");
        payload.put("headers", resolvedHeaders);
        return true;
    }

    @SuppressWarnings("unchecked")
    private void cleanupConnectionSecrets(
            Map<String, Object> payload, OnboardingDraftDto draft, Set<String> cleanedRefs) {
        List<Map<String, Object>> connections = (List<Map<String, Object>>) payload.get("connections");
        if (connections == null) return;
        for (Map<String, Object> conn : connections) {
            cleanupMapSecrets(conn, draft, cleanedRefs);
        }
    }

    @SuppressWarnings("unchecked")
    private void cleanupMcpServerHeaderSecrets(
            Map<String, Object> payload, OnboardingDraftDto draft, Set<String> cleanedRefs) {
        Object rawMcp = payload.get("mcp");
        if (!(rawMcp instanceof Map<?, ?> rawMap)) return;
        Map<String, Object> mcp = (Map<String, Object>) rawMap;
        Object rawServers = mcp.get("servers");
        if (!(rawServers instanceof List<?> servers)) return;
        for (Object rawServer : servers) {
            if (rawServer instanceof Map<?, ?> rawServerMap) {
                Map<String, Object> srv = (Map<String, Object>) rawServerMap;
                cleanupMcpHeaderMap(srv, draft, cleanedRefs);
                cleanupMapSecrets(srv, draft, cleanedRefs);
            }
        }
    }

    private void cleanupMcpHeaderMap(Map<String, Object> mcp, OnboardingDraftDto draft, Set<String> cleanedRefs) {
        Object rawRefs = mcp.get("headerVaultRefs");
        if (!(rawRefs instanceof Map<?, ?> refs)) return;
        for (Object rawRef : refs.values()) {
            if (!(rawRef instanceof String ref)) continue;
            if (cleanedRefs == null || cleanedRefs.add(ref)) {
                try {
                    vault.remove(ref, draft.ownerId());
                    log.debugf("[ONBOARDING] Cleaned staged MCP header %s draft %s", ref, draft.draftId());
                } catch (Exception e) {
                    log.warnf("[ONBOARDING] Failed clean staged MCP header %s: %s", ref, e.getMessage());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void cleanupMcpHeaderSecrets(
            Map<String, Object> payload, OnboardingDraftDto draft, Set<String> cleanedRefs) {
        Map<String, Object> mcp = (Map<String, Object>) payload.get("mcp");
        if (mcp == null) return;
        Map<String, String> headerVaultRefs = (Map<String, String>) mcp.get("headerVaultRefs");
        if (headerVaultRefs == null) return;
        for (String ref : headerVaultRefs.values()) {
            if (cleanedRefs == null || cleanedRefs.add(ref)) {
                try {
                    vault.remove(ref, draft.ownerId());
                    log.debugf("[ONBOARDING] Cleaned staged MCP header %s for draft %s", ref, draft.draftId());
                } catch (Exception e) {
                    log.warnf("[ONBOARDING] Failed to clean staged MCP header %s: %s", ref, e.getMessage());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean scrubOptionalSections(Map<String, Object> payload, String ownerId) {
        boolean modified = false;
        String[] sections = {"integrations", "memory", "channels", "security", "advanced"};
        for (String sec : sections) {
            Object raw = payload.get(sec);
            if (raw instanceof Map<?, ?> map) {
                modified |= scrubMapSecrets((Map<String, Object>) map, ownerId);
            }
        }
        return modified;
    }

    @SuppressWarnings("unchecked")
    private void cleanupOptionalSecrets(
            Map<String, Object> payload, OnboardingDraftDto draft, Set<String> cleanedRefs) {
        String[] sections = {"integrations", "memory", "channels", "security", "advanced"};
        for (String sec : sections) {
            Object raw = payload.get(sec);
            if (raw instanceof Map<?, ?> map) {
                cleanupMapSecrets((Map<String, Object>) map, draft, cleanedRefs);
            }
        }
    }

    boolean scrubMapSecrets(Map<String, Object> map, String ownerId) {
        if (map == null || map.isEmpty()) return false;
        boolean modified = false;
        Map<String, Object> toAdd = new LinkedHashMap<>();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String k = entry.getKey();
            Object v = entry.getValue();
            if (isSecretValue(k, v) && !(v instanceof Map<?, ?>)) {
                String secretValue = encodeSecretValue(v);
                if (secretValue != null) {
                    String refKey = k.endsWith("VaultRef") ? k : k + "VaultRef";
                    String ref = newVaultKeyRef();
                    storeSecret(ref, secretValue, ownerId);
                    toAdd.put(refKey, ref);
                }
                toRemove.add(k);
                modified = true;
            } else if (v instanceof Map<?, ?> childMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) childMap;
                modified |= scrubMapSecrets(nested, ownerId);
            } else if (v instanceof List<?> childList) {
                for (Object item : childList) {
                    if (item instanceof Map<?, ?> itemMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> nested = (Map<String, Object>) itemMap;
                        modified |= scrubMapSecrets(nested, ownerId);
                    }
                }
            }
        }
        for (String r : toRemove) {
            map.remove(r);
        }
        map.putAll(toAdd);
        return modified;
    }

    /**
     * Renders a secret-named value as storable text, or null when there is nothing to vault
     * (null, blank, or already a vault/MCP header reference). Non-String values are JSON-encoded
     * so material under a secret-named key is preserved in the vault instead of discarded.
     */
    private String encodeSecretValue(Object value) {
        if (value instanceof String str) {
            if (str.isBlank()
                    || str.startsWith("vault-key-")
                    || str.startsWith("mcp-header-")
                    || str.startsWith("staged-ref-")) {
                return null;
            }
            return str;
        }
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[ONBOARDING] Unable to encode secret value for vaulting; leaving unvaulted", e);
            return null;
        }
    }

    /**
     * Stores secret material under the given reference. The plaintext bytes are zeroed as soon
     * as the vault has consumed them.
     */
    private void storeSecret(String ref, String value, String ownerId) {
        byte[] secretBytes = value.getBytes(StandardCharsets.UTF_8);
        try {
            vault.store(ref, secretBytes, ownerId);
        } finally {
            Arrays.fill(secretBytes, (byte) 0);
        }
    }

    private static String newVaultKeyRef() {
        return "vault-key-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String newMcpHeaderRef() {
        return "mcp-header-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private void cleanupMapSecrets(Map<String, Object> map, OnboardingDraftDto draft, Set<String> cleanedRefs) {
        if (map == null || map.isEmpty()) return;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String k = entry.getKey();
            Object v = entry.getValue();
            if ((k.endsWith("VaultRef") || k.endsWith("Ref"))
                    && v instanceof String ref
                    && ref.startsWith("vault-key-")) {
                if (cleanedRefs == null || cleanedRefs.add(ref)) {
                    try {
                        vault.remove(ref, draft.ownerId());
                        log.debugf("[ONBOARDING] Cleaned staged secret %s for draft %s", ref, draft.draftId());
                    } catch (Exception e) {
                        log.warnf("[ONBOARDING] Failed to clean staged secret %s: %s", ref, e.getMessage());
                    }
                }
            } else if (v instanceof Map<?, ?> childMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) childMap;
                cleanupMapSecrets(nested, draft, cleanedRefs);
            } else if (v instanceof List<?> childList) {
                for (Object item : childList) {
                    if (item instanceof Map<?, ?> itemMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> nested = (Map<String, Object>) itemMap;
                        cleanupMapSecrets(nested, draft, cleanedRefs);
                    }
                }
            }
        }
    }

    static boolean isSecretKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        return (lower.contains("secret")
                        || lower.contains("token")
                        || lower.contains("password")
                        || lower.contains("key")
                        || lower.contains("pass"))
                && !lower.endsWith("vaultref");
    }

    /**
     * Recognizes plaintext submitted through legacy UI fields named {@code *VaultRef}.
     * A real vault reference is retained; a credential placed in that field is scrubbed.
     */
    static boolean isSecretValue(String key, Object value) {
        if (isSecretKey(key)) {
            return true;
        }
        if (key == null || value == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (!lower.endsWith("vaultref") || isVaultReference(value)) {
            return false;
        }
        String baseKey = key.substring(0, key.length() - "VaultRef".length());
        return isSecretKey(baseKey);
    }

    private static boolean isVaultReference(Object value) {
        return value instanceof String reference
                && (reference.startsWith("vault-key-")
                        || reference.startsWith("mcp-header-")
                        || reference.startsWith("staged-ref-"));
    }
}
