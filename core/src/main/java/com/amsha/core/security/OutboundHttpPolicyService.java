package com.amsha.core.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.InetAddress;
import java.net.URI;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.routing.provider.NetworkProfilePolicy;
import com.amsha.persistence.api.LlmConnectionDto;

import org.jboss.logging.Logger;

/**
 * Central outbound HTTP policy service. Every outbound HTTP request (provider catalog probe,
 * provider API call, MCP server connection, OAuth callback) must pass through this service before
 * any network I/O is performed. The policy enforces:
 *
 * <ul>
 *   <li>SSRF protection: blocks requests to private/link-local/loopback/metadata IPs.</li>
 *   <li>Literal-address SSRF protection; the approved HTTP transport must revalidate DNS and
 *       every redirect hop after approval and immediately before connecting.</li>
 *   <li>TLS enforcement: non-local URLs must use HTTPS.</li>
 *   <li>Proxy policy: per-connection proxy requirements.</li>
 *   <li>Timeout policy: configurable per-purpose timeouts.</li>
 *   <li>HITL gating: sensitive operations (catalog probe, OAuth, MCP) require human approval.</li>
 * </ul>
 *
 * Denial events are logged to the audit service for security monitoring.
 */
@ApplicationScoped
public class OutboundHttpPolicyService {

    private static final Logger log = Logger.getLogger(OutboundHttpPolicyService.class);

    @Inject
    AuditService auditService;

    /**
     * Evaluates the outbound policy for a request to the given URL.
     *
     * @param url the target URL
     * @param purpose the purpose of the request (for HITL gating and audit)
     * @param connection optional connection context (for per-connection policy)
     * @param actorUserId the user initiating the request (for HITL and audit)
     * @return the policy decision
     */
    public OutboundPolicyDecision evaluate(
            String url, OutboundPurpose purpose, LlmConnectionDto connection, String actorUserId) {
        return evaluate(url, purpose, connection, actorUserId, false);
    }

    /**
     * Evaluates the outbound policy for a request.
     *
     * @param url the target URL
     * @param purpose the purpose of the request (for HITL gating and audit)
     * @param connection optional connection context (for per-connection policy)
     * @param actorUserId the user initiating the request (for HITL and audit)
     * @param preApproved true when the operation has already cleared HITL via the onboarding
     *     approval lifecycle (e.g. an MCP connection committed through the saga). When true the
     *     HITL gate is skipped; SSRF/TLS/proxy/host checks still apply.
     * @return the policy decision
     */
    public OutboundPolicyDecision evaluate(
            String url, OutboundPurpose purpose, LlmConnectionDto connection, String actorUserId, boolean preApproved) {
        // 1. Parse and validate URL.
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            return deny("INVALID_URL", purpose, actorUserId);
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();

        if (scheme == null || host == null) {
            return deny("MISSING_SCHEME_OR_HOST", purpose, actorUserId);
        }

        // 2. TLS enforcement: non-local URLs must use HTTPS.
        if (!isLocalhost(host) && !"https".equalsIgnoreCase(scheme)) {
            return deny("TLS_REQUIRED", purpose, actorUserId);
        }

        // Literal addresses can be checked without DNS/network I/O. DNS and redirect-hop
        // revalidation must be performed by the approved HTTP transport after HITL approval.
        if (!isLocalhost(host) && isBlockedLiteralAddress(host)) {
            return deny("BLOCKED_LITERAL_ADDRESS", purpose, actorUserId);
        }

        // 4. Per-connection policy (if connection provided).
        if (connection != null) {
            OutboundPolicyDecision connDecision = evaluateConnectionPolicy(connection, url, purpose, actorUserId);
            if (connDecision.isDenied()) return connDecision;
        }

        // 5. HITL gating for sensitive operations.
        if (!preApproved && requiresHitl(purpose)) {
            return OutboundPolicyDecision.requireHitl(
                    "Operation " + purpose + " requires human approval before proceeding", purpose, actorUserId);
        }

        return OutboundPolicyDecision.allow(purpose, actorUserId);
    }

    /**
     * Evaluates per-connection network profile policy (host allow-list, proxy, etc.).
     */
    private OutboundPolicyDecision evaluateConnectionPolicy(
            LlmConnectionDto connection, String url, OutboundPurpose purpose, String actorUserId) {
        if (connection.networkProfileJson() == null
                || connection.networkProfileJson().isBlank()) {
            return OutboundPolicyDecision.allow(purpose, actorUserId);
        }

        NetworkProfilePolicy policy = NetworkProfilePolicy.fromJson(connection.networkProfileJson());

        // Host allow-list / deny-list.
        if (!policy.isHostAllowed(url)) {
            return deny("CONNECTION_EGRESS_DENIED", purpose, actorUserId);
        }

        // Proxy enforcement.
        if (policy.requireProxy()) {
            String proxyUrl = policy.proxyUrl().orElse(null);
            if (proxyUrl == null || proxyUrl.isBlank()) {
                return deny("PROXY_REQUIRED", purpose, actorUserId);
            }
            // Proxy transport enforcement is deferred to the HTTP client layer.
            // The policy service only validates the requirement; the client must honor it.
        }

        return OutboundPolicyDecision.allow(purpose, actorUserId);
    }

    private OutboundPolicyDecision deny(String category, OutboundPurpose purpose, String actorUserId) {
        log.warnf("[OUTBOUND-POLICY] Denied category=%s purpose=%s", category, purpose);
        auditService.recordMandatory(
                "outbound-policy",
                actorUserId != null ? actorUserId : "system",
                "system",
                SecurityEventType.MCP_CONNECTION_VALIDATION_FAILED,
                "action=outbound_denied purpose=" + purpose + " category=" + category);
        return OutboundPolicyDecision.deny(category, purpose, actorUserId);
    }

    private static boolean requiresHitl(OutboundPurpose purpose) {
        return switch (purpose) {
            case CATALOG_PROBE, OAUTH_CALLBACK, MCP_CONNECT -> true;
            case PROVIDER_API_CALL, PROVIDER_MODEL_DISCOVERY, MCP_TOOL_CALL -> false;
        };
    }

    private static boolean isLocalhost(String host) {
        if (host == null) return false;
        // Java's URI.getHost() returns IPv6 addresses with brackets, e.g. "[::1]".
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1");
    }

    private static boolean isBlockedLiteralAddress(String host) {
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!normalized.matches("[0-9a-fA-F:.]+")) return false;
        try {
            InetAddress address = InetAddress.getByName(normalized);
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                return true;
            }
            byte[] bytes = address.getAddress();
            return bytes.length == 4
                    && (bytes[0] & 0xff) == 100
                    && ((bytes[1] & 0xff) >= 64 && (bytes[1] & 0xff) <= 127);
        } catch (Exception _) {
            return true;
        }
    }
}
