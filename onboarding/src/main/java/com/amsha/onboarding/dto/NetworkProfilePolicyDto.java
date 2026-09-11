package com.amsha.onboarding.dto;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.llm.api.NetworkProfilePolicy;
import com.amsha.llm.api.NetworkProfilePolicy.EgressMode;
import com.amsha.llm.api.NetworkProfilePolicy.TlsTrustMode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Structured DTO for per-connection network policy settings (TLS trust mode, timeouts, proxy,
 * and no-proxy patterns). Provides client/server validation against maximum bounds and fails
 * closed on malformed configurations.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NetworkProfilePolicyDto(
        String egressMode,
        List<String> allowedHosts,
        Boolean requireProxy,
        String proxyUrl,
        String proxyCredentialVaultRef,
        List<String> noProxyHosts,
        Integer connectTimeoutMs,
        Integer readTimeoutMs,
        String tlsTrustMode,
        String customCaVaultRef) {

    public static final int MAX_TIMEOUT_MS = 300_000;

    public static NetworkProfilePolicyDto defaultPolicy() {
        return fromPolicy(NetworkProfilePolicy.allowAll());
    }

    public static NetworkProfilePolicyDto fromPolicy(NetworkProfilePolicy policy) {
        if (policy == null) {
            return defaultPolicy();
        }
        return new NetworkProfilePolicyDto(
                policy.egressMode() != null ? policy.egressMode().name() : "ALLOW_ALL",
                policy.allowedHosts() != null ? policy.allowedHosts() : List.of(),
                policy.requireProxy(),
                policy.proxyUrl().orElse(null),
                policy.proxyCredentialVaultRef().orElse(null),
                policy.noProxyHosts() != null ? policy.noProxyHosts() : List.of(),
                policy.connectTimeoutMs() > 0 ? policy.connectTimeoutMs() : null,
                policy.readTimeoutMs() > 0 ? policy.readTimeoutMs() : null,
                policy.tlsTrustMode() != null ? policy.tlsTrustMode().name() : "SYSTEM",
                policy.customCaVaultRef().orElse(null));
    }

    public static NetworkProfilePolicyDto fromJson(String json) {
        if (json == null || json.isBlank()) {
            return defaultPolicy();
        }
        if (!NetworkProfilePolicy.isValidJson(json)) {
            throw new IllegalArgumentException("Invalid network profile JSON: " + json);
        }
        return fromPolicy(NetworkProfilePolicy.fromJson(json));
    }

    /**
     * Validates constraints on network profile fields.
     *
     * @return null if valid, or an error description if invalid.
     */
    public String validate() {
        if (egressMode != null && !egressMode.isBlank()) {
            try {
                EgressMode.valueOf(egressMode.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException _) {
                return "Invalid egressMode: " + egressMode + ". Must be ALLOW_ALL, ALLOW_LIST, or DENY_ALL";
            }
        }
        if (tlsTrustMode != null && !tlsTrustMode.isBlank()) {
            try {
                TlsTrustMode.valueOf(tlsTrustMode.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException _) {
                return "Invalid tlsTrustMode: " + tlsTrustMode + ". Must be SYSTEM or CUSTOM_CA";
            }
        }
        if ("CUSTOM_CA".equalsIgnoreCase(tlsTrustMode) && (customCaVaultRef == null || customCaVaultRef.isBlank())) {
            return "customCaVaultRef is required when tlsTrustMode is CUSTOM_CA";
        }
        if ("ALLOW_LIST".equalsIgnoreCase(egressMode) && (allowedHosts == null || allowedHosts.isEmpty())) {
            return "allowedHosts cannot be empty when egressMode is ALLOW_LIST";
        }
        if (Boolean.TRUE.equals(requireProxy)) {
            if (proxyUrl == null || proxyUrl.isBlank()) {
                return "proxyUrl is required when requireProxy is true";
            }
            try {
                URI uri = URI.create(proxyUrl);
                if (uri.getHost() == null
                        || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
                    return "proxyUrl must be an absolute http or https URI";
                }
                if (OutboundHttpPolicyService.isBlockedLiteralAddress(uri.getHost())) {
                    return "proxyUrl host must not be a loopback, link-local, or private IP literal";
                }
            } catch (Exception _) {
                return "proxyUrl must be a valid URI";
            }
        }
        if (connectTimeoutMs != null && (connectTimeoutMs < 0 || connectTimeoutMs > MAX_TIMEOUT_MS)) {
            return "connectTimeoutMs must be between 0 and " + MAX_TIMEOUT_MS + " ms";
        }
        if (readTimeoutMs != null && (readTimeoutMs < 0 || readTimeoutMs > MAX_TIMEOUT_MS)) {
            return "readTimeoutMs must be between 0 and " + MAX_TIMEOUT_MS + " ms";
        }
        return null;
    }

    /**
     * Serializes this DTO to a JSON string safe for {@link NetworkProfilePolicy#fromJson(String)}.
     */
    public String toJson(ObjectMapper mapper) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(
                "egressMode",
                egressMode != null && !egressMode.isBlank() ? egressMode.toUpperCase(Locale.ROOT) : "ALLOW_ALL");
        if (allowedHosts != null && !allowedHosts.isEmpty()) {
            map.put("allowedHosts", allowedHosts);
        }
        if (Boolean.TRUE.equals(requireProxy)) {
            map.put("requireProxy", true);
            if (proxyUrl != null && !proxyUrl.isBlank()) {
                map.put("proxyUrl", proxyUrl);
            }
            if (proxyCredentialVaultRef != null && !proxyCredentialVaultRef.isBlank()) {
                map.put("proxyCredentialVaultRef", proxyCredentialVaultRef);
            }
        } else {
            map.put("requireProxy", false);
        }
        if (noProxyHosts != null && !noProxyHosts.isEmpty()) {
            map.put("noProxyHosts", noProxyHosts);
        }
        if (connectTimeoutMs != null && connectTimeoutMs > 0) {
            map.put("connectTimeoutMs", connectTimeoutMs);
        }
        if (readTimeoutMs != null && readTimeoutMs > 0) {
            map.put("readTimeoutMs", readTimeoutMs);
        }
        map.put(
                "tlsTrustMode",
                tlsTrustMode != null && !tlsTrustMode.isBlank() ? tlsTrustMode.toUpperCase(Locale.ROOT) : "SYSTEM");
        if ("CUSTOM_CA".equalsIgnoreCase(tlsTrustMode) && customCaVaultRef != null && !customCaVaultRef.isBlank()) {
            map.put("customCaVaultRef", customCaVaultRef);
        }
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize network profile policy: " + e.getMessage(), e);
        }
    }
}
