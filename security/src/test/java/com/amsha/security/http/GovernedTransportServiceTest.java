package com.amsha.security.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.CredentialDetector;
import com.amsha.core.security.DefaultCredentialDetector;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Unit tests for the governed transport facade moved in S7: audit-before-send, approval-token
 * consumption semantics, and the convenience GET path. Uses an ephemeral loopback server.
 */
class GovernedTransportServiceTest {

    private GovernedTransportService service;
    private ApprovalReceiptIssuer receiptIssuer;
    private AuditService auditService;
    private HttpServer server;
    private String baseUrl;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        service = new GovernedTransportService();
        inject("policyService", fixedPolicy(OutboundPolicyDecision.allow(OutboundPurpose.PROVIDER_API_CALL, "ok")));
        inject("vault", Mockito.mock(SecretVault.class));
        auditService = Mockito.mock(AuditService.class);
        inject("auditService", auditService);
        receiptIssuer = new ApprovalReceiptIssuer();
        inject("approvalReceiptIssuer", receiptIssuer);

        Instance<CredentialDetector> detInstance = Mockito.mock(Instance.class);
        when(detInstance.isResolvable()).thenReturn(true);
        when(detInstance.get()).thenReturn(new DefaultCredentialDetector());
        inject("credentialDetectorInstance", detInstance);

        meterRegistry = new SimpleMeterRegistry();
        Instance<MeterRegistry> meterInstance = Mockito.mock(Instance.class);
        when(meterInstance.isResolvable()).thenReturn(true);
        when(meterInstance.get()).thenReturn(meterRegistry);
        inject("meterRegistryInstance", meterInstance);

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

    @Test
    void validateEgressFailsClosedOnBlankOrInvalidUrl() {
        assertThrows(
                OutboundHttpException.class,
                () -> service.validateEgress(null, OutboundPurpose.PROVIDER_API_CALL, "user-1"));
        assertThrows(
                OutboundHttpException.class,
                () -> service.validateEgress("   ", OutboundPurpose.PROVIDER_API_CALL, "user-1"));
        assertThrows(
                OutboundHttpException.class,
                () -> service.validateEgress("not-a-valid-uri:::", OutboundPurpose.PROVIDER_API_CALL, "user-1"));
    }

    @Test
    void postBlocksEgressWhenPayloadContainsCredentials() {
        String secretPayload = "{\"api_key\": \"sk-proj-1234567890abcdef12345678\"}";
        var ex = assertThrows(
                OutboundHttpException.class,
                () -> service.post(
                        baseUrl + "/ok",
                        secretPayload,
                        "application/json",
                        OutboundPurpose.PROVIDER_API_CALL,
                        null,
                        "user-1",
                        true));
        assertTrue(ex.getMessage().contains("detected credentials"));
    }

    @Test
    void sendRecordsOperationalMetricsAndEnrichedAudit() {
        HttpResponse<String> resp =
                service.get(baseUrl + "/ok", OutboundPurpose.PROVIDER_API_CALL, null, "user-1", true);
        assertEquals(200, resp.statusCode());
        assertEquals(
                1.0,
                meterRegistry
                        .counter("amsha.transport.requests.total", "purpose", "provider_api_call", "status", "success")
                        .count());
        assertEquals(
                1L,
                meterRegistry
                        .timer("amsha.transport.duration", "purpose", "provider_api_call", "status", "success")
                        .count());
    }

    @Test
    void sendWithApprovalTokenAttributesAuditToApprovedByUserIdWhenCallerNull() {
        String token = receiptIssuer.issueForExplicitApproval(
                "approver-admin",
                "op-99",
                OutboundPurpose.PROVIDER_API_CALL,
                URI.create(baseUrl).getHost());

        HttpResponse<String> resp = service.send(
                baseUrl + "/ok",
                OutboundPurpose.PROVIDER_API_CALL,
                null,
                null,
                false,
                token,
                getRequest(baseUrl + "/ok"),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());

        ArgumentCaptor<String> actorCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditService)
                .record(
                        eq("governed-transport"),
                        actorCaptor.capture(),
                        eq("system"),
                        eq(SecurityEventType.TOOL_EXECUTED),
                        anyString());
        assertEquals("approver-admin", actorCaptor.getValue());
    }

    @Test
    void sendExecutesMandatoryAuditBeforeNetworkDispatch() {
        AtomicInteger serverHits = new AtomicInteger(0);
        server.createContext("/tracked-ok", exchange -> {
            serverHits.incrementAndGet();
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        HttpResponse<String> resp = service.send(
                baseUrl + "/tracked-ok",
                OutboundPurpose.PROVIDER_API_CALL,
                null,
                "user-1",
                false,
                getRequest(baseUrl + "/tracked-ok"),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertEquals(1, serverHits.get());

        verify(auditService)
                .recordMandatory(
                        eq("governed-transport"),
                        eq("user-1"),
                        eq("system"),
                        eq(SecurityEventType.TOOL_INVOKED),
                        anyString());
        verify(auditService)
                .record(
                        eq("governed-transport"),
                        eq("user-1"),
                        eq("system"),
                        eq(SecurityEventType.TOOL_EXECUTED),
                        anyString());
    }

    @Test
    void sendFailsClosedWhenMandatoryAuditFailsAndDoesNotHitWire() {
        AtomicInteger serverHits = new AtomicInteger(0);
        server.createContext("/tracked-fail", exchange -> {
            serverHits.incrementAndGet();
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        doThrow(new RuntimeException("Audit persistence failure"))
                .when(auditService)
                .recordMandatory(
                        eq("governed-transport"),
                        eq("user-fail"),
                        eq("system"),
                        eq(SecurityEventType.TOOL_INVOKED),
                        anyString());

        assertThrows(
                Exception.class,
                () -> service.send(
                        baseUrl + "/tracked-fail",
                        OutboundPurpose.PROVIDER_API_CALL,
                        null,
                        "user-fail",
                        false,
                        getRequest(baseUrl + "/tracked-fail"),
                        HttpResponse.BodyHandlers.ofString()));

        assertEquals(0, serverHits.get());
    }

    @Test
    void postSendAuditFailureDoesNotFailSuccessfulRequest() {
        doThrow(new RuntimeException("Post-send audit store error"))
                .when(auditService)
                .record(
                        eq("governed-transport"),
                        eq("user-safe"),
                        eq("system"),
                        eq(SecurityEventType.TOOL_EXECUTED),
                        anyString());

        HttpResponse<String> resp = service.send(
                baseUrl + "/ok",
                OutboundPurpose.PROVIDER_API_CALL,
                null,
                "user-safe",
                false,
                getRequest(baseUrl + "/ok"),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertEquals("pong", resp.body());
    }
}
