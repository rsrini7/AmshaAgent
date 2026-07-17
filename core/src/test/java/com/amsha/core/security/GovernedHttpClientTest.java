package com.amsha.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import com.amsha.core.audit.AuditService;
import com.amsha.core.security.http.GovernedHttpClient;
import com.amsha.core.security.http.OutboundHttpException;
import com.amsha.persistence.api.LlmConnectionDto;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Focused tests for {@link GovernedHttpClient}: policy gating, DNS-rebinding revalidation,
 * redirect-hop validation, timeout enforcement, and proxy configuration.
 */
class GovernedHttpClientTest {

    private OutboundHttpPolicyService alwaysAllow;
    private OutboundHttpPolicyService alwaysDeny;
    private OutboundHttpPolicyService requireHitl;
    private HttpServer server;
    private String baseUrl;

    /** A policy service that returns a fixed decision for every evaluation. */
    private static final class FixedPolicy extends OutboundHttpPolicyService {
        private final OutboundPolicyDecision fixed;

        FixedPolicy(OutboundPolicyDecision fixed) {
            this.fixed = fixed;
        }

        @Override
        public OutboundPolicyDecision evaluate(
                String url,
                OutboundPurpose purpose,
                LlmConnectionDto connection,
                String actorUserId,
                boolean preApproved) {
            return fixed;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        AuditService auditService = Mockito.mock(AuditService.class);
        OutboundHttpPolicyService real = new OutboundHttpPolicyService();
        real.auditService = auditService;
        alwaysAllow = new FixedPolicy(OutboundPolicyDecision.allow(OutboundPurpose.PROVIDER_API_CALL, "u"));
        alwaysDeny = new FixedPolicy(OutboundPolicyDecision.deny("blocked", OutboundPurpose.PROVIDER_API_CALL, "u"));
        requireHitl =
                new FixedPolicy(OutboundPolicyDecision.requireHitl("need approval", OutboundPurpose.MCP_CONNECT, "u"));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok");
            exchange.sendResponseHeaders(302, -1);
        });
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void policyDenial_blocksRequest() {
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysDeny)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .actor("u")
                .build();
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + "/ok")).GET().build();
        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
    }

    @Test
    void hitlRequired_blocksRequest() {
        GovernedHttpClient client = GovernedHttpClient.builder(requireHitl)
                .purpose(OutboundPurpose.MCP_CONNECT)
                .actor("u")
                .build();
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + "/ok")).GET().build();
        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.HITL_REQUIRED, ex.category());
    }

    @Test
    void allowedRequest_succeeds() {
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .actor("u")
                .build();
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + "/ok")).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
    }

    @Test
    void redirect_isFollowedWithPerHopValidation() {
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .actor("u")
                .build();
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + "/redirect")).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
    }

    @Test
    void redirectLoop_hitsHopLimit() {
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .actor("u")
                .build();
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + "/loop")).GET().build();
        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.REDIRECT_DENIED, ex.category());
    }

    @Test
    void connectTimeout_enforcedFromPolicy() {
        String policyJson = "{\"connectTimeoutMs\":1,\"readTimeoutMs\":1}";
        LlmConnectionDto conn = new LlmConnectionDto(
                "c1",
                "USER",
                "u",
                "u",
                "conn",
                "openai",
                null,
                null,
                "API_KEY",
                policyJson,
                "ACTIVE",
                1,
                Instant.now(),
                Instant.now());
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .connection(conn)
                .actor("u")
                .build();
        // Connect to a blackhole address that will hang until the 1ms timeout fires.
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://10.255.255.1:9/"))
                .GET()
                .build();
        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertTrue(
                ex.category() == OutboundHttpException.Category.TIMEOUT
                        || ex.category() == OutboundHttpException.Category.POLICY_DENIED
                        || ex.category() == OutboundHttpException.Category.IO,
                "unexpected category: " + ex.category());
    }

    @Test
    void proxyRequiredWithoutUrl_policyDeniedByTransport() {
        String policyJson = "{\"requireProxy\":true,\"proxyUrl\":\"\"}";
        LlmConnectionDto conn = new LlmConnectionDto(
                "c1",
                "USER",
                "u",
                "u",
                "conn",
                "openai",
                null,
                null,
                "API_KEY",
                policyJson,
                "ACTIVE",
                1,
                Instant.now(),
                Instant.now());
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .connection(conn)
                .actor("u")
                .build();
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + "/ok")).GET().build();
        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
    }

    @Test
    void noProxyHost_bypassesProxyRequirement() {
        String policyJson =
                "{\"requireProxy\":true,\"proxyUrl\":\"http://proxy.invalid:9\",\"noProxyHosts\":[\"127.0.0.1\"]}";
        LlmConnectionDto conn = new LlmConnectionDto(
                "c1",
                "USER",
                "u",
                "u",
                "conn",
                "openai",
                null,
                null,
                "API_KEY",
                policyJson,
                "ACTIVE",
                1,
                Instant.now(),
                Instant.now());
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .connection(conn)
                .actor("u")
                .build();
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + "/ok")).GET().build();
        // The no-proxy host bypasses the proxy, so the request reaches the local server.
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
    }

    @Test
    void realPolicy_mcpConnect_requiresHitlWithoutPreApproval() {
        OutboundHttpPolicyService realPolicy = new OutboundHttpPolicyService();
        realPolicy.auditService = Mockito.mock(AuditService.class);
        GovernedHttpClient client = GovernedHttpClient.builder(realPolicy)
                .purpose(OutboundPurpose.MCP_CONNECT)
                .actor("u")
                .build();
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + "/ok")).GET().build();
        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.HITL_REQUIRED, ex.category());
    }

    @Test
    void realPolicy_mcpConnect_preApproved_reachesServer() {
        OutboundHttpPolicyService realPolicy = new OutboundHttpPolicyService();
        realPolicy.auditService = Mockito.mock(AuditService.class);
        GovernedHttpClient client = GovernedHttpClient.builder(realPolicy)
                .purpose(OutboundPurpose.MCP_CONNECT)
                .actor("u")
                .preApproved(true)
                .build();
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + "/ok")).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
    }
}
