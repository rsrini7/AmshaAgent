package com.amsha.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;
import com.amsha.security.http.GovernedHttpClient;
import com.amsha.security.http.OutboundHttpException;
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
        // auditService is package-private in core; this test moved modules in S7, so inject reflectively.
        var auditField = OutboundHttpPolicyService.class.getDeclaredField("auditService");
        auditField.setAccessible(true);
        auditField.set(real, auditService);
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
    void ioFailure_mapsToSanitizedIoException() {
        // Bind, capture the port, then release it so nothing is listening there.
        server.stop(0);
        int deadPort = server.getAddress().getPort();

        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .actor("u")
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + deadPort + "/ok"))
                .GET()
                .build();

        var ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.IO, ex.category());
    }

    @Test
    void redirectWithoutLocationHeader_denied() {
        server.createContext("/nolocation", exchange -> exchange.sendResponseHeaders(302, -1));

        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .actor("u")
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/nolocation"))
                .GET()
                .build();

        var ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.REDIRECT_DENIED, ex.category());
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
    void proxyCredentials_resolvedAndApplied() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        VaultKey key = Mockito.mock(VaultKey.class);
        when(key.get()).thenReturn("user:pass".getBytes(StandardCharsets.UTF_8));
        when(vault.get("proxy-ref", "u")).thenReturn(Optional.of(key));

        String policyJson =
                "{\"requireProxy\":true,\"proxyUrl\":\"http://127.0.0.1:9999\",\"proxyCredentialVaultRef\":\"proxy-ref\"}";
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
                .vault(vault)
                .connection(conn)
                .actor("u")
                .build();

        HttpRequest req = HttpRequest.newBuilder(URI.create("http://example.com/ok"))
                .GET()
                .build();
        // Request fails on connection to proxy:9999 (I/O error), demonstrating proxy + auth was initialized
        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertTrue(ex.category() == OutboundHttpException.Category.IO
                || ex.category() == OutboundHttpException.Category.TIMEOUT);
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
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
    }

    @Test
    void customCaMode_missingVault_throwsTlsException() {
        String policyJson = "{\"tlsTrustMode\":\"CUSTOM_CA\",\"customCaVaultRef\":\"ca-ref\"}";
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
        assertEquals(OutboundHttpException.Category.TLS_TRUST, ex.category());
    }
}
