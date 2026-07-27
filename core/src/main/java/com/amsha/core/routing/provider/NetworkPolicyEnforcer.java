package com.amsha.core.routing.provider;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.Optional;

import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.core.security.OutboundPolicyDecision;
import com.amsha.core.security.OutboundPurpose;
import com.amsha.persistence.api.LlmConnectionDto;

import org.jboss.logging.Logger;

/**
 * Enforces per-connection outbound-network policy derived from {@code LlmConnectionDto.networkProfileJson}.
 *
 * <p>Resolution of a connection's credential must satisfy the egress policy before any network call
 * is made. A denied host fails fast with a safe (non-secret) error so routing can fall back or
 * surface a field error rather than leaking a connection attempt to a disallowed endpoint.</p>
 */
@ApplicationScoped
public class NetworkPolicyEnforcer {

    private static final Logger log = Logger.getLogger(NetworkPolicyEnforcer.class);

    @Inject
    OutboundHttpPolicyService outboundHttpPolicyService;

    /** Returns the parsed policy for a connection (never null; blank/unparseable → ALLOW_ALL). */
    public NetworkProfilePolicy policyFor(LlmConnectionDto connection) {
        return NetworkProfilePolicy.fromJson(connection.networkProfileJson());
    }

    /**
     * Asserts that the resolved base URL is permitted by the connection's egress policy.
     * Enforces:
     * <ul>
     *   <li>TLS: non-local URLs must use HTTPS (prevents plaintext credential transmission).</li>
     *   <li>Proxy: a proxy-required connection is refused until the selected client implementation
     *       supports configuring a per-connection proxy transport.</li>
     *   <li>Host allow-list / deny-list: the URL host must be permitted by the policy's egress mode.</li>
     * </ul>
     * Redirect and DNS-rebinding safety are enforced at the HTTP client layer (connection timeout,
     * redirect policy, address resolution), not at the policy layer.
     *
     * @throws NetworkPolicyViolation when any transport safety check fails
     */
    public void assertEgressAllowed(LlmConnectionDto connection, String baseUrl) {
        if (outboundHttpPolicyService != null) {
            OutboundPolicyDecision decision =
                    outboundHttpPolicyService.evaluate(baseUrl, OutboundPurpose.PROVIDER_API_CALL, connection, null);
            if (decision.isDenied()) {
                throw new NetworkPolicyViolation(
                        "Connection egress denied: " + decision.reason().orElse("POLICY_DENIED"));
            }
        }
        NetworkProfilePolicy policy = policyFor(connection);

        // TLS enforcement: non-local URLs must use HTTPS.
        if (!isLocalhost(baseUrl) && !baseUrl.toLowerCase().startsWith("https://")) {
            log.warnf("Egress denied for connection %s: non-local URL must use HTTPS: %s", connection.id(), baseUrl);
            throw new NetworkPolicyViolation(
                    "Connection " + connection.id() + " must use HTTPS for non-local URL: " + baseUrl);
        }

        // Proxy enforcement.
        if (policy.requireProxy()) {
            String proxyUrl = policy.proxyUrl().orElse(null);
            if (proxyUrl == null || proxyUrl.isBlank()) {
                log.warnf(
                        "Egress denied for connection %s: requireProxy=true but no proxyUrl configured",
                        connection.id());
                throw new NetworkPolicyViolation(
                        "Connection " + connection.id() + " requires a proxy but no proxy URL is configured");
            }
            throw new NetworkPolicyViolation("Connection " + connection.id()
                    + " requires a proxy, but per-connection proxy transport is not configured");
        }

        // Host allow-list / deny-list.
        if (!policy.isHostAllowed(baseUrl)) {
            String host = hostOf(baseUrl);
            log.warnf("Egress denied for connection %s: host=%s mode=%s", connection.id(), host, policy.egressMode());
            throw new NetworkPolicyViolation(
                    "Connection " + connection.id() + " may not reach host: " + (host == null ? baseUrl : host));
        }
    }

    /** Returns the proxy URL for a connection's policy, if one is configured and required. */
    public Optional<String> proxyUrl(LlmConnectionDto connection) {
        return policyFor(connection).proxyUrl();
    }

    private static String hostOf(String url) {
        try {
            return new URI(url).getHost();
        } catch (Exception _) {
            return null;
        }
    }

    private static boolean isLocalhost(String url) {
        String host = hostOf(url);
        if (host == null) return false;
        // Normalize IPv6 bracket-wrapped form to match OutboundHttpPolicyService.
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1");
    }
}
