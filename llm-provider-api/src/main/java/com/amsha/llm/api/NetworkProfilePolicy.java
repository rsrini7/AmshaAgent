package com.amsha.llm.api;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Per-connection outbound-network policy, parsed from {@code LlmConnectionDto.networkProfileJson}.
 *
 * <p>Shape (all fields optional):
 * <pre>
 * {
 *   "egressMode": "ALLOW_ALL" | "ALLOW_LIST" | "DENY_ALL",
 *   "allowedHosts": ["api.openai.com"],
 *   "requireProxy": false,
 *   "proxyUrl": "http://proxy.internal:3128",
 *   "proxyCredentialVaultRef": "vault-key-...",
 *   "noProxyHosts": ["localhost", "*.internal"],
 *   "connectTimeoutMs": 5000,
 *   "readTimeoutMs": 30000,
 *   "tlsTrustMode": "SYSTEM" | "CUSTOM_CA",
 *   "customCaVaultRef": "vault-key-..."
 * }
 * </pre>
 * A blank policy (null or empty) defaults to {@code ALLOW_ALL} so an unset policy never blocks a
 * previously-working connection. An <em>unparseable</em> policy is a corruption signal and fails
 * closed by throwing rather than silently degrading to {@code ALLOW_ALL} — a corrupt committed
 * profile must not grant unrestricted egress. The policy is read-only and never logs
 * secret material. Proxy credentials and custom-CA material are opaque vault references; they are
 * never persisted in process memory beyond the short-lived transport build. Certificate-verification
 * bypass is not representable: {@code tlsTrustMode} may be {@code SYSTEM} or an explicit
 * {@code CUSTOM_CA} vault reference, never {@code NONE}.</p>
 */
public final class NetworkProfilePolicy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum EgressMode {
        ALLOW_ALL,
        ALLOW_LIST,
        DENY_ALL
    }

    /** TLS trust posture. {@code NONE} is intentionally absent: production TLS verification is mandatory. */
    public enum TlsTrustMode {
        SYSTEM,
        CUSTOM_CA
    }

    private final EgressMode egressMode;
    private final List<String> allowedHosts;
    private final boolean requireProxy;
    private final String proxyUrl;
    private final String proxyCredentialVaultRef;
    private final List<String> noProxyHosts;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final TlsTrustMode tlsTrustMode;
    private final String customCaVaultRef;

    private NetworkProfilePolicy(
            EgressMode egressMode,
            List<String> allowedHosts,
            boolean requireProxy,
            String proxyUrl,
            String proxyCredentialVaultRef,
            List<String> noProxyHosts,
            int connectTimeoutMs,
            int readTimeoutMs,
            TlsTrustMode tlsTrustMode,
            String customCaVaultRef) {
        this.egressMode = egressMode;
        this.allowedHosts = allowedHosts;
        this.requireProxy = requireProxy;
        this.proxyUrl = proxyUrl;
        this.proxyCredentialVaultRef = proxyCredentialVaultRef;
        this.noProxyHosts = noProxyHosts;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.tlsTrustMode = tlsTrustMode;
        this.customCaVaultRef = customCaVaultRef;
    }

    public static NetworkProfilePolicy allowAll() {
        return new NetworkProfilePolicy(
                EgressMode.ALLOW_ALL, List.of(), false, null, null, List.of(), 0, 0, TlsTrustMode.SYSTEM, null);
    }

    /**
     * Parses a per-connection network policy from its JSON blob. A blank blob (null or empty) defaults
     * to {@code ALLOW_ALL} (an unset policy); an unparseable blob fails closed by throwing, so a
     * corrupt stored profile cannot degrade to unrestricted egress.
     */
    public static NetworkProfilePolicy fromJson(String json) {
        if (json == null || json.isBlank()) {
            return allowAll();
        }
        try {
            Map<String, Object> node = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
            EgressMode mode = EgressMode.ALLOW_ALL;
            Object rawMode = node.get("egressMode");
            if (rawMode instanceof String s && !s.isBlank()) {
                mode = EgressMode.valueOf(s.toUpperCase(Locale.ROOT));
            }
            List<String> hosts = List.of();
            Object rawHosts = node.get("allowedHosts");
            if (rawHosts instanceof List<?> list) {
                hosts = list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
            }
            boolean proxy = Boolean.TRUE.equals(node.get("requireProxy"));
            String proxyUrl = node.get("proxyUrl") instanceof String p ? p : null;
            String proxyCredRef = node.get("proxyCredentialVaultRef") instanceof String r ? r : null;
            List<String> noProxy = List.of();
            Object rawNoProxy = node.get("noProxyHosts");
            if (rawNoProxy instanceof List<?> list) {
                noProxy = list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
            }
            int connectMs = asInt(node.get("connectTimeoutMs"), 0);
            int readMs = asInt(node.get("readTimeoutMs"), 0);
            TlsTrustMode tls = TlsTrustMode.SYSTEM;
            Object rawTls = node.get("tlsTrustMode");
            if (rawTls instanceof String s && !s.isBlank()) {
                tls = TlsTrustMode.valueOf(s.toUpperCase(Locale.ROOT));
            }
            String caRef = node.get("customCaVaultRef") instanceof String c ? c : null;
            if (tls == TlsTrustMode.CUSTOM_CA && (caRef == null || caRef.isBlank())) {
                // A custom-CA mode without a vault reference is unsafe; fail closed to SYSTEM.
                tls = TlsTrustMode.SYSTEM;
                caRef = null;
            }
            return new NetworkProfilePolicy(
                    mode, hosts, proxy, proxyUrl, proxyCredRef, noProxy, connectMs, readMs, tls, caRef);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid network-profile JSON; refusing to default to ALLOW_ALL", e);
        }
    }

    /** Returns whether a submitted network-profile document has a safe, supported shape. */
    public static boolean isValidJson(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            Map<String, Object> node = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
            Object rawMode = node.get("egressMode");
            EgressMode mode = rawMode instanceof String value
                    ? EgressMode.valueOf(value.toUpperCase(Locale.ROOT))
                    : EgressMode.ALLOW_ALL;
            Object rawTls = node.get("tlsTrustMode");
            TlsTrustMode tls = rawTls instanceof String value
                    ? TlsTrustMode.valueOf(value.toUpperCase(Locale.ROOT))
                    : TlsTrustMode.SYSTEM;
            if (mode == EgressMode.ALLOW_LIST
                    && (!(node.get("allowedHosts") instanceof List<?> hosts) || hosts.isEmpty())) return false;
            if (Boolean.TRUE.equals(node.get("requireProxy")) && !isAbsoluteHttpUri(node.get("proxyUrl"))) return false;
            if (tls == TlsTrustMode.CUSTOM_CA
                    && (!(node.get("customCaVaultRef") instanceof String ref) || ref.isBlank())) return false;
            return positiveOrAbsent(node.get("connectTimeoutMs")) && positiveOrAbsent(node.get("readTimeoutMs"));
        } catch (Exception _) {
            return false;
        }
    }

    public static boolean isAbsoluteHttpUri(Object value) {
        if (!(value instanceof String raw) || raw.isBlank()) return false;
        try {
            URI uri = URI.create(raw.trim());
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (Exception _) {
            return false;
        }
    }

    private static boolean positiveOrAbsent(Object value) {
        return value == null || (value instanceof Number number && number.intValue() > 0);
    }

    private static int asInt(Object raw, int fallback) {
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException _) {
                return fallback;
            }
        }
        return fallback;
    }

    public EgressMode egressMode() {
        return egressMode;
    }

    public List<String> allowedHosts() {
        return allowedHosts;
    }

    public boolean requireProxy() {
        return requireProxy;
    }

    public Optional<String> proxyUrl() {
        return Optional.ofNullable(proxyUrl);
    }

    public Optional<String> proxyCredentialVaultRef() {
        return Optional.ofNullable(proxyCredentialVaultRef);
    }

    public List<String> noProxyHosts() {
        return noProxyHosts;
    }

    /** Connect timeout in milliseconds; 0 means use the transport default. */
    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    /** Read/request timeout in milliseconds; 0 means use the transport default. */
    public int readTimeoutMs() {
        return readTimeoutMs;
    }

    public TlsTrustMode tlsTrustMode() {
        return tlsTrustMode;
    }

    public Optional<String> customCaVaultRef() {
        return Optional.ofNullable(customCaVaultRef);
    }

    /**
     * Returns true if the host matches the no-proxy exclusion list. Matching supports exact host and
     * {@code *.suffix} wildcards; the policy still enforces TLS and SSRF for matched hosts, only the
     * proxy hop is bypassed.
     */
    public boolean isNoProxyHost(String host) {
        if (host == null || noProxyHosts.isEmpty()) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        return noProxyHosts.stream().anyMatch(pattern -> {
            String p = pattern.toLowerCase(Locale.ROOT);
            if (p.equals(lower)) return true;
            if (p.startsWith("*.")) {
                String suffix = p.substring(1);
                return lower.endsWith(suffix);
            }
            return false;
        });
    }

    /** True when a request to the given base URL is permitted under this policy. */
    public boolean isHostAllowed(String baseUrl) {
        if (egressMode == EgressMode.ALLOW_ALL) return true;
        if (egressMode == EgressMode.DENY_ALL) return false;
        String host = hostOf(baseUrl);
        return host != null && allowedHosts.stream().anyMatch(h -> h.equalsIgnoreCase(host));
    }

    private static String hostOf(String url) {
        try {
            return new URI(url).getHost();
        } catch (Exception _) {
            return null;
        }
    }
}
