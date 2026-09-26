package com.amsha.core.classifier.selfhosted;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Validates endpoint URIs and host bindings for self-hosted decision providers (§7.5).
 * Enforces local-only loopback isolation: requires explicit 127.0.0.1 binding and strictly
 * rejects wildcard exposure (0.0.0.0), localhost (to avoid resolution drift and DNS rebinding),
 * and any external or non-loopback addresses.
 */
public final class SelfHostedEndpointValidator {

    public static final String LOOPBACK_IPV4 = "127.0.0.1";

    private SelfHostedEndpointValidator() {}

    /**
     * Validates that the endpoint URI satisfies local-only loopback constraints.
     *
     * @param endpoint endpoint URI to validate
     * @return validated endpoint URI
     * @throws SecurityException if the host is wildcard (0.0.0.0), localhost, or non-loopback
     * @throws IllegalArgumentException if endpoint is null, malformed, non-HTTP/HTTPS, or has an invalid port
     */
    public static URI validateLoopbackEndpoint(URI endpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException("Endpoint URI must not be null");
        }
        String scheme = endpoint.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Endpoint URI scheme must be http or https, got: " + scheme);
        }
        if (endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException("Endpoint URI must not contain user information");
        }
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Endpoint URI host must not be blank");
        }
        validateLoopbackHost(host);
        int port = endpoint.getPort();
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Endpoint URI port must be between 1 and 65535, got: " + port);
        }
        return endpoint;
    }

    /**
     * Validates that the host address is strictly the approved IPv4 loopback address (127.0.0.1).
     *
     * @param host host string or IP address
     * @throws SecurityException if the host is wildcard (0.0.0.0), localhost, or non-loopback
     */
    public static void validateLoopbackHost(String host) {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new SecurityException("Host must not be blank");
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if ("0.0.0.0".equals(normalized)) {
            throw new SecurityException(
                    "Wildcard binding 0.0.0.0 is strictly forbidden for local-only self-hosted decision provider");
        }
        if ("localhost".equals(normalized)) {
            throw new SecurityException(
                    "Host 'localhost' is rejected; local-only provider must bind explicitly to 127.0.0.1 to avoid resolution drift and DNS rebinding");
        }
        if (!LOOPBACK_IPV4.equals(normalized)) {
            throw new SecurityException(
                    "Self-hosted provider requires loopback binding to 127.0.0.1; rejected host: '" + host + "'");
        }
    }
}
