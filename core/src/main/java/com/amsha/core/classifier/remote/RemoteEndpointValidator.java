package com.amsha.core.classifier.remote;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

import com.amsha.core.security.OutboundHttpPolicyService;

/**
 * Validates endpoint URIs and hosts for remote decision providers (§3, §7.6).
 * Enforces transport and network egress constraints:
 * <ul>
 *   <li>Requires HTTPS for all non-loopback endpoints.</li>
 *   <li>Permits HTTP only for local loopback addresses (127.0.0.1, localhost, ::1) for testing/stubs.</li>
 *   <li>Strictly rejects wildcard bindings (0.0.0.0, ::).</li>
 *   <li>Strictly rejects URIs containing user information (userinfo).</li>
 *   <li>Reuses canonical {@link OutboundHttpPolicyService#isBlockedLiteralAddress(String)} to reject
 *       SSRF literal addresses (link-local, site-local, private, metadata IPs).</li>
 * </ul>
 */
public final class RemoteEndpointValidator {

    private RemoteEndpointValidator() {}

    /**
     * Validates that the endpoint URI satisfies remote provider security and TLS constraints.
     *
     * @param endpoint endpoint URI to validate
     * @return validated endpoint URI
     * @throws SecurityException if the scheme is insecure (HTTP on non-loopback), wildcard, or blocked literal address
     * @throws IllegalArgumentException if endpoint is null, malformed, non-HTTP(S), contains userinfo, or has an invalid port
     */
    public static URI validateRemoteEndpoint(URI endpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException("Remote endpoint URI must not be null");
        }
        String scheme = endpoint.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Remote endpoint URI scheme must be http or https, got: " + scheme);
        }
        if (endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException("Remote endpoint URI must not contain user information");
        }
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Remote endpoint URI host must not be blank");
        }
        validateRemoteHost(host, scheme);
        int port = endpoint.getPort();
        if (port != -1 && (port <= 0 || port > 65535)) {
            throw new IllegalArgumentException("Remote endpoint URI port must be between 1 and 65535, got: " + port);
        }
        return endpoint;
    }

    /**
     * Validates host address and scheme according to remote security constraints.
     *
     * @param host host name or IP address
     * @param scheme URI scheme ("http" or "https")
     * @throws SecurityException if constraints are violated
     */
    public static void validateRemoteHost(String host, String scheme) {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new SecurityException("Host must not be blank");
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }

        // 1. Wildcard bindings are strictly forbidden
        if ("0.0.0.0".equals(normalized) || "::".equals(normalized)) {
            throw new SecurityException("Wildcard binding is strictly forbidden for remote decision provider: " + host);
        }

        // 2. Loopback allowance for testing/stubs
        boolean isLoopback =
                "127.0.0.1".equals(normalized) || "localhost".equals(normalized) || "::1".equals(normalized);

        // 3. TLS enforcement: non-loopback endpoints must use HTTPS
        if (!isLoopback && !"https".equalsIgnoreCase(scheme)) {
            throw new SecurityException(
                    "Remote decision provider requires HTTPS for non-loopback endpoints; got: " + scheme);
        }

        // 4. Literal address SSRF protection: reject private, link-local, site-local, metadata IPs
        if (!isLoopback && OutboundHttpPolicyService.isBlockedLiteralAddress(host)) {
            throw new SecurityException("Remote decision provider endpoint is a blocked literal address: " + host);
        }
    }
}
