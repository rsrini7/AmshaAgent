package com.amsha.core.security.http;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.core.security.OutboundPurpose;
import com.amsha.core.security.SecretVault;
import com.amsha.persistence.api.LlmConnectionDto;

import org.jboss.logging.Logger;

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
@ApplicationScoped
public class GovernedTransportService {

    private static final Logger log = Logger.getLogger(GovernedTransportService.class);

    @Inject
    OutboundHttpPolicyService policyService;

    @Inject
    SecretVault vault;

    @Inject
    AuditService auditService;

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
        log.debugf(
                "Sending governed request to %s (purpose: %s, actor: %s, preApproved: %b)",
                url, purpose, actorUserId, preApproved);
        GovernedHttpClient client = GovernedHttpClient.builder(policyService)
                .vault(vault)
                .purpose(purpose)
                .connection(connection)
                .actor(actorUserId)
                .preApproved(preApproved)
                .build();
        auditService.record(
                "governed-transport",
                actorUserId != null ? actorUserId : "system",
                "system",
                SecurityEventType.TOOL_EXECUTED,
                "governed_send purpose=" + purpose + " host=" + request.uri().getHost());
        return client.send(request, bodyHandler);
    }

    /**
     * Convenience method for GET requests with string response.
     */
    public HttpResponse<String> get(
            String url, OutboundPurpose purpose, LlmConnectionDto connection, String actorUserId, boolean preApproved) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(GovernedHttpClient.DEFAULT_READ_TIMEOUT_MS))
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", "Amsha Agent/1.0 (+https://github.com/amsha)")
                .GET()
                .build();
        return send(url, purpose, connection, actorUserId, preApproved, request, HttpResponse.BodyHandlers.ofString());
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(GovernedHttpClient.DEFAULT_READ_TIMEOUT_MS))
                .header("Content-Type", contentType)
                .header("Accept", "application/json")
                .header("User-Agent", "Amsha Agent/1.0 (+https://github.com/amsha)")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(url, purpose, connection, actorUserId, preApproved, request, HttpResponse.BodyHandlers.ofString());
    }
}
