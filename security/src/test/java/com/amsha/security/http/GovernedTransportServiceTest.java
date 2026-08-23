package com.amsha.security.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.amsha.core.audit.AuditService;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for the governed transport facade moved in S7: audit-before-send, approval-token
 * consumption semantics, and the convenience GET path. Uses an ephemeral loopback server.
 */
class GovernedTransportServiceTest {

    private GovernedTransportService service;
    private ApprovalReceiptIssuer receiptIssuer;
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        service = new GovernedTransportService();
        inject("policyService", fixedPolicy(OutboundPolicyDecision.allow(OutboundPurpose.PROVIDER_API_CALL, "ok")));
        inject("vault", Mockito.mock(SecretVault.class));
        inject("auditService", Mockito.mock(AuditService.class));
        receiptIssuer = new ApprovalReceiptIssuer();
        inject("approvalReceiptIssuer", receiptIssuer);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            byte[] body = "pong".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private OutboundHttpPolicyService fixedPolicy(OutboundPolicyDecision decision) {
        return new OutboundHttpPolicyService() {
            @Override
            public OutboundPolicyDecision evaluate(
                    String url,
                    OutboundPurpose purpose,
                    LlmConnectionDto connection,
                    String actorUserId,
                    boolean preApproved) {
                return decision;
            }
        };
    }

    private void inject(String field, Object value) throws Exception {
        var f = GovernedTransportService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    private HttpRequest getRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url)).GET().build();
    }

    @Test
    void sendWithoutTokenRecordsAuditAndSucceeds() {
        HttpResponse<String> resp = service.send(
                baseUrl + "/ok",
                OutboundPurpose.PROVIDER_API_CALL,
                null,
                "user-1",
                false,
                getRequest(baseUrl + "/ok"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        verify(service.auditService, atLeastOnce()).record(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void validApprovalTokenAuthorizesAndSetsEffectiveActor() {
        String token = receiptIssuer.issueForExplicitApproval(
                "user-9",
                "op-1",
                OutboundPurpose.PROVIDER_API_CALL,
                URI.create(baseUrl).getHost());

        // Verification consumes the receipt before any check can reject it: a wrong-actor
        // presentation burns the single-use token (anti-replay), so user-1 cannot ride on it.
        var mismatch = assertThrows(
                OutboundHttpException.class,
                () -> service.send(
                        baseUrl + "/ok",
                        OutboundPurpose.PROVIDER_API_CALL,
                        null,
                        "user-1",
                        false,
                        token,
                        getRequest(baseUrl + "/ok"),
                        HttpResponse.BodyHandlers.ofString()));
        assertTrue(mismatch.getMessage().contains("different actor"));
    }

    @Test
    void validTokenAuthorizesTheBoundActor() {
        String token = receiptIssuer.issueForExplicitApproval(
                "user-9",
                "op-2",
                OutboundPurpose.PROVIDER_API_CALL,
                URI.create(baseUrl).getHost());

        HttpResponse<String> resp = service.send(
                baseUrl + "/ok",
                OutboundPurpose.PROVIDER_API_CALL,
                null,
                "user-9",
                false,
                token,
                getRequest(baseUrl + "/ok"),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        // Single-use: replaying the same token must now be rejected as HITL-required.
        assertThrows(
                OutboundHttpException.class,
                () -> service.send(
                        baseUrl + "/ok",
                        OutboundPurpose.PROVIDER_API_CALL,
                        null,
                        "user-9",
                        false,
                        token,
                        getRequest(baseUrl + "/ok"),
                        HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    void invalidApprovalTokenThrowsHitl() {
        assertThrows(
                OutboundHttpException.class,
                () -> service.send(
                        baseUrl + "/ok",
                        OutboundPurpose.PROVIDER_API_CALL,
                        null,
                        "user-1",
                        false,
                        "bogus-token",
                        getRequest(baseUrl + "/ok"),
                        HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    void convenienceGetExecutesThroughTheSameGate() {
        HttpResponse<String> resp =
                service.get(baseUrl + "/ok", OutboundPurpose.PROVIDER_API_CALL, null, "user-2", true);
        assertEquals(200, resp.statusCode());
        assertEquals("pong", resp.body());
    }

    @Test
    void policyDenialSurfacesAsOutboundHttpException() throws Exception {
        inject(
                "policyService",
                fixedPolicy(OutboundPolicyDecision.deny("blocked", OutboundPurpose.PROVIDER_API_CALL, "u")));
        assertThrows(
                OutboundHttpException.class,
                () -> service.get(baseUrl + "/ok", OutboundPurpose.PROVIDER_API_CALL, null, "user-2", false));
    }
}
