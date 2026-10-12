package com.amsha.security.http;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.amsha.common.http.HttpProtocol;
import com.amsha.common.util.LogUtil;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.CredentialDetector;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.Unremovable;

/**
 * Application-scoped entry point for all governed outbound HTTP work. Every outbound HTTP request
 * (provider catalog probe, provider API call, MCP server connection, OAuth callback, model
 * discovery) must route through this service rather than constructing an {@link HttpClient} directly.
 *
 * <p>Usage:
 * <pre>{@code
 * GovernedResponse<String> resp = governedTransport.send(
 *     url, OutboundPurpose.CATALOG_PROBE, connection, actorUserId,
 *     false, // preApproved
 *     bodyHandler);
 * }</pre>
 *
 * <p>The service enforces SSRF, TLS, DNS revalidation, redirect-hop, proxy, timeout, and HITL
 * policy automatically. Audit events are recorded for every request.</p>
 */
@Unremovable
@ApplicationScoped
public class GovernedTransportService {

    private static final Logger log = Logger.getLogger(GovernedTransportService.class);

    @Inject
    OutboundHttpPolicyService policyService;

    @Inject
    SecretVault vault;

    @Inject
    AuditService auditService;

    @Inject
    ApprovalReceiptIssuer approvalReceiptIssuer;

    @Inject
    Instance<CredentialDetector> credentialDetectorInstance;

    @Inject
    Instance<MeterRegistry> meterRegistryInstance;

    /**
     * Executes a governed HTTP request with full policy enforcement.
     *
     * @param url the target URL
     * @param purpose the outbound purpose (for HITL gating and audit)
     * @param connection optional connection DTO (for per-connection policy); null for default policy
     * @param actorUserId the user initiating the request
     * @param preApproved true when the operation already cleared HITL (e.g. committed via saga)
     * @param request the HTTP request to execute
     * @param bodyHandler the response body handler
     * @param <T> response body type
     * @return the HTTP response
     * @throws OutboundHttpException on policy denial, HITL, DNS rebinding, redirect, or timeout
     */
    public <T> HttpResponse<T> send(
            String url,
            OutboundPurpose purpose,
            LlmConnectionDto connection,
            String actorUserId,
            boolean preApproved,
            HttpRequest request,
            HttpResponse.BodyHandler<T> bodyHandler) {
        return send(url, purpose, connection, actorUserId, preApproved, null, request, bodyHandler);
    }

    /**
     * Validates an outbound destination against security policies (SSRF, TLS, proxy, IP allowlist)
     * without sending data, recording an audit event.
     *
     * @param url target destination URL
     * @param purpose outbound purpose
     * @param actorUserId actor requesting outbound egress
     * @throws OutboundHttpException if egress is denied by policy or destination is invalid
     */
    public void validateEgress(String url, OutboundPurpose purpose, String actorUserId) {
        validateEgress(url, purpose, actorUserId, false);
    }

    /**
     * Validates an outbound destination against security policies with optional pre-approval state.
     */
    public void validateEgress(String url, OutboundPurpose purpose, String actorUserId, boolean preApproved) {
        if (url == null || url.isBlank()) {
            throw OutboundHttpException.policy("Egress URL must not be null or blank");
        }
        URI uri;
        try {
            uri = URI.create(url);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Missing host in URL: " + url);
            }
        } catch (Exception _) {
            throw OutboundHttpException.policy("Invalid egress URL: " + url);
        }
        var decision = policyService.evaluate(url, purpose, null, actorUserId, preApproved);
        if (decision.isDenied()) {
            throw OutboundHttpException.policy(
                    "Egress denied to " + url + ": " + decision.reason().orElse("policy denial"));
        }
        if (decision.requiresHitl()) {
            throw OutboundHttpException.hitl("HITL approval required for egress to " + url + ": "
                    + decision.reason().orElse("approval required"));
        }
        auditService.record(
                "governed-transport",
                actorUserId != null ? actorUserId : "system",
                "operator",
                SecurityEventType.TOOL_EXECUTED,
                "governed_egress_validated purpose=" + purpose + " host=" + uri.getHost());
    }

    /**
     * Executes a governed HTTP request, consuming a checkpoint-issued single-use approval token when
     * supplied. A valid token authorizes exactly this purpose+host and sets the effective actor; an
     * invalid/expired/mismatched token is rejected as HITL-required. When {@code approvalToken} is
     * null the {@code preApproved} flag is used as-is.
     */
    public <T> HttpResponse<T> send(
            String url,
            OutboundPurpose purpose,
            LlmConnectionDto connection,
            String actorUserId,
            boolean preApproved,
            String approvalToken,
            HttpRequest request,
            HttpResponse.BodyHandler<T> bodyHandler) {
        boolean effectivePreApproved = preApproved;
        String effectiveActor = actorUserId;
        if (approvalToken != null) {
            OutboundApprovalReceipt receipt = approvalReceiptIssuer.verifyAndConsume(
                    approvalToken, purpose, URI.create(url).getHost());
            if (receipt == null) {
                throw OutboundHttpException.hitl("Approval token is invalid, expired, or does not match this request");
            }
            if (actorUserId != null && !actorUserId.equals(receipt.approvedByUserId())) {
                throw OutboundHttpException.hitl("Approval token belongs to a different actor");
            }
            effectivePreApproved = true;
            effectiveActor = receipt.approvedByUserId();
        }
        if (credentialDetectorInstance != null && credentialDetectorInstance.isResolvable()) {
            var detector = credentialDetectorInstance.get();
            var uriDet = detector.detect(url);
            if (uriDet.detected()) {
                auditService.record(
                        "governed-transport",
                        effectiveActor != null ? effectiveActor : "system",
                        "system",
                        SecurityEventType.UNAUTHORIZED_ACCESS,
                        "credential_egress_blocked type=" + uriDet.credentialType() + " purpose=" + purpose);
                throw OutboundHttpException.policy("Outbound egress blocked: destination URI contains credentials ("
                        + uriDet.credentialType() + ")");
            }
        }
        log.debugf(
                "Sending governed request to %s (purpose: %s, actor: %s, preApproved: %b)",
                url, purpose, effectiveActor, effectivePreApproved);
        GovernedHttpClient client = GovernedHttpClient.builder(policyService)
                .vault(vault)
                .purpose(purpose)
                .connection(connection)
                .actor(effectiveActor)
                .preApproved(effectivePreApproved)
                .build();

        // Mandatory authorization/audit gating must happen before outbound network I/O
        auditService.recordMandatory(
                "governed-transport",
                effectiveActor != null ? effectiveActor : "system",
                "system",
                SecurityEventType.TOOL_INVOKED,
                "governed_send_dispatch purpose=" + purpose + " host="
                        + request.uri().getHost() + " preApproved=" + effectivePreApproved);

        long startNs = System.nanoTime();
        try {
            HttpResponse<T> response = client.send(request, bodyHandler);
            long durationNanos = System.nanoTime() - startNs;
            long durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos);
            GovernedTransportMetrics.recordRequest(
                    GovernedTransportMetrics.resolve(meterRegistryInstance), purpose, "success", durationNanos);
            try {
                auditService.record(
                        "governed-transport",
                        effectiveActor != null ? effectiveActor : "system",
                        "system",
                        SecurityEventType.TOOL_EXECUTED,
                        "governed_send purpose=" + purpose + " host="
                                + request.uri().getHost() + " status=success durationMs=" + durationMs);
            } catch (Exception auditEx) {
                log.warnf(
                        "GovernedTransportService: failed to record post-send completion audit: %s",
                        LogUtil.rootMessage(auditEx));
            }
            return response;
        } catch (Exception e) {
            long durationNanos = System.nanoTime() - startNs;
            long durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos);
            String status = mapExceptionToStatus(e);
            GovernedTransportMetrics.recordRequest(
                    GovernedTransportMetrics.resolve(meterRegistryInstance), purpose, status, durationNanos);
            try {
                auditService.record(
                        "governed-transport",
                        effectiveActor != null ? effectiveActor : "system",
                        "system",
                        SecurityEventType.TOOL_FAILED,
                        "governed_send_failed purpose=" + purpose + " host="
                                + request.uri().getHost() + " status=" + status + " durationMs=" + durationMs);
            } catch (Exception auditEx) {
                e.addSuppressed(auditEx);
                log.warnf(
                        "GovernedTransportService: failed to record audit on send failure: %s",
                        LogUtil.rootMessage(auditEx));
            }
            throw e;
        }
    }

    /**
     * Convenience method for GET requests with string response.
     */
    public HttpResponse<String> get(
            String url, OutboundPurpose purpose, LlmConnectionDto connection, String actorUserId, boolean preApproved) {
        return get(url, purpose, connection, actorUserId, preApproved, null);
    }

    /**
     * Convenience method for GET requests with string response, consuming an approval token.
     */
    public HttpResponse<String> get(
            String url,
            OutboundPurpose purpose,
            LlmConnectionDto connection,
            String actorUserId,
            boolean preApproved,
            String approvalToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(GovernedHttpClient.DEFAULT_READ_TIMEOUT_MS))
                .header(HttpProtocol.HEADER_ACCEPT, "application/json, text/plain, */*")
                .header("User-Agent", "Amsha Agent/1.0 (+https://github.com/amsha)")
                .GET()
                .build();
        return send(
                url,
                purpose,
                connection,
                actorUserId,
                preApproved,
                approvalToken,
                request,
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Convenience method for POST requests with string body and string response.
     */
    public HttpResponse<String> post(
            String url,
            String body,
            String contentType,
            OutboundPurpose purpose,
            LlmConnectionDto connection,
            String actorUserId,
            boolean preApproved) {
        return post(url, body, contentType, purpose, connection, actorUserId, preApproved, null);
    }

    /**
     * Convenience method for POST requests with string body and string response, consuming an
     * approval token.
     */
    public HttpResponse<String> post(
            String url,
            String body,
            String contentType,
            OutboundPurpose purpose,
            LlmConnectionDto connection,
            String actorUserId,
            boolean preApproved,
            String approvalToken) {
        if (body != null && credentialDetectorInstance != null && credentialDetectorInstance.isResolvable()) {
            var det = credentialDetectorInstance.get().detect(body);
            if (det.detected()) {
                auditService.record(
                        "governed-transport",
                        actorUserId != null ? actorUserId : "system",
                        "system",
                        SecurityEventType.UNAUTHORIZED_ACCESS,
                        "credential_egress_blocked type=" + det.credentialType() + " purpose=" + purpose);
                throw OutboundHttpException.policy("Outbound egress blocked: payload contains detected credentials ("
                        + det.credentialType() + ")");
            }
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(GovernedHttpClient.DEFAULT_READ_TIMEOUT_MS))
                .header(HttpProtocol.HEADER_CONTENT_TYPE, contentType)
                .header(HttpProtocol.HEADER_ACCEPT, HttpProtocol.MEDIA_TYPE_JSON)
                .header("User-Agent", "Amsha Agent/1.0 (+https://github.com/amsha)")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(
                url,
                purpose,
                connection,
                actorUserId,
                preApproved,
                approvalToken,
                request,
                HttpResponse.BodyHandlers.ofString());
    }

    private static String mapExceptionToStatus(Exception e) {
        if (e instanceof OutboundHttpException ohe) {
            return ohe.category().name().toLowerCase(Locale.ROOT);
        }
        return "error";
    }
}
