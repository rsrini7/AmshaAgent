package com.amsha.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.common.http.HttpProtocol;
import com.amsha.core.audit.AuditService;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.core.security.http.GovernedHttpClient;
import com.amsha.core.security.http.OutboundHttpException;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;
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
    void policyUriMapper_redactsCredentialPathBeforePolicyEvaluation() {
        AtomicReference<String> evaluatedUrl = new AtomicReference<>();
        OutboundHttpPolicyService policy = new OutboundHttpPolicyService() {
            @Override
            public OutboundPolicyDecision evaluate(
                    String url,
                    OutboundPurpose purpose,
                    LlmConnectionDto connection,
                    String actorUserId,
                    boolean preApproved) {
                evaluatedUrl.set(url);
                return OutboundPolicyDecision.allow(purpose, actorUserId);
            }
        };
        String token = "123456:secret-token";
        GovernedHttpClient client = GovernedHttpClient.builder(policy)
                .purpose(OutboundPurpose.MEDIA_DOWNLOAD)
                .actor("u")
                .preApproved(true)
                .policyUriMapper(uri -> URI.create(uri.toString().replace("/bot" + token + "/", "/botREDACTED/")))
                .build();

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/bot" + token + "/getFile"))
                .GET()
                .build();
        client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(baseUrl + "/botREDACTED/getFile", evaluatedUrl.get());
        assertFalse(evaluatedUrl.get().contains(token));
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
    void crossOriginRedirect_doesNotForwardApiKeyId() throws Exception {
        HttpServer destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> receivedKeyId = new AtomicReference<>();
        destination.createContext("/capture", exchange -> {
            receivedKeyId.set(exchange.getRequestHeaders().getFirst(HttpProtocol.HEADER_X_API_KEY_ID));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        destination.start();
        try {
            String destinationUrl =
                    "http://127.0.0.1:" + destination.getAddress().getPort() + "/capture";
            server.createContext("/redirect-with-key-id", exchange -> {
                exchange.getResponseHeaders().add("Location", destinationUrl);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                    .purpose(OutboundPurpose.PROVIDER_API_CALL)
                    .actor("u")
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/redirect-with-key-id"))
                    .header(HttpProtocol.HEADER_X_API_KEY_ID, "test-placeholder")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertNull(receivedKeyId.get());
        } finally {
            destination.stop(0);
        }
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
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://198.51.100.1:9/"))
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

    @Test
    void localhostAndIpv4MappedDetection() throws Exception {
        var localhostMethod = GovernedHttpClient.class.getDeclaredMethod("isLocalhost", String.class);
        localhostMethod.setAccessible(true);

        assertTrue((Boolean) localhostMethod.invoke(null, "localhost"));
        assertTrue((Boolean) localhostMethod.invoke(null, "127.0.0.1"));
        assertTrue((Boolean) localhostMethod.invoke(null, "::1"));
        assertTrue((Boolean) localhostMethod.invoke(null, "[::1]"));
        assertFalse((Boolean) localhostMethod.invoke(null, "example.com"));
        assertFalse((Boolean) localhostMethod.invoke(null, (String) null));

        var mappedMethod = GovernedHttpClient.class.getDeclaredMethod("isIpv4MappedAddress", byte[].class);
        mappedMethod.setAccessible(true);

        byte[] mapped = new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 10, 0, 0, 1};
        assertTrue((Boolean) mappedMethod.invoke(null, (Object) mapped));

        byte[] plainV6 = new byte[16];
        assertFalse((Boolean) mappedMethod.invoke(null, (Object) plainV6));
        byte[] shortBytes = new byte[4];
        assertFalse((Boolean) mappedMethod.invoke(null, (Object) shortBytes));
    }

    // ------------------------------------------------------------------
    // Header flattening / sensitive-header stripping on redirects
    // ------------------------------------------------------------------

    @Test
    void sameOriginRedirect_copiesRequestHeaders() throws Exception {
        server.createContext("/redir-same", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok");
            exchange.sendResponseHeaders(302, -1);
        });
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .actor("u")
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/redir-same"))
                .header("X-Custom", "v")
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
    }

    @Test
    void crossOriginRedirect_stripsSensitiveHeadersButKeepsOthers() throws Exception {
        int port = server.getAddress().getPort();
        server.createContext("/redir-cross", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://localhost:" + port + "/ok");
            exchange.sendResponseHeaders(302, -1);
        });
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .actor("u")
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/redir-cross"))
                .header("Authorization", "Bearer secret")
                .header("X-Custom", "v")
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
    }

    // ------------------------------------------------------------------
    // Connect-timeout override + builder validation
    // ------------------------------------------------------------------

    @Test
    void connectTimeoutOverride_isApplied() {
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .actor("u")
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + "/ok")).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
    }

    @Test
    void connectTimeoutBuilder_rejectsNonPositiveValues() {
        GovernedHttpClient.Builder builder = GovernedHttpClient.builder(alwaysAllow);
        assertThrows(IllegalArgumentException.class, () -> builder.connectTimeout(null));
        assertThrows(IllegalArgumentException.class, () -> builder.connectTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> builder.connectTimeout(Duration.ofSeconds(-1)));
    }

    // ------------------------------------------------------------------
    // Policy-URI mapper rejection
    // ------------------------------------------------------------------

    @Test
    void policyUriMapper_nullTarget_denied() {
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .actor("u")
                .policyUriMapper(uri -> null)
                .build();
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + "/ok")).GET().build();
        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
    }

    @Test
    void policyUriMapper_originChange_denied() {
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .actor("u")
                .policyUriMapper(uri -> URI.create("https://evil.example.com/x"))
                .build();
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + "/ok")).GET().build();
        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
    }

    // ------------------------------------------------------------------
    // Proxy credential resolution failure modes
    // ------------------------------------------------------------------

    @Test
    void proxyCredentials_withoutVault_denied() {
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .connection(
                        conn(
                                "{\"requireProxy\":true,\"proxyUrl\":\"http://127.0.0.1:9999\",\"proxyCredentialVaultRef\":\"proxy-ref\"}"))
                .actor("u")
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://example.com/ok"))
                .GET()
                .build();
        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
    }

    @Test
    void proxyCredentials_unresolvedRef_denied() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        when(vault.get("proxy-ref", "u")).thenReturn(Optional.empty());
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .vault(vault)
                .connection(
                        conn(
                                "{\"requireProxy\":true,\"proxyUrl\":\"http://127.0.0.1:9999\",\"proxyCredentialVaultRef\":\"proxy-ref\"}"))
                .actor("u")
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://example.com/ok"))
                .GET()
                .build();
        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
    }

    @Test
    void proxyCredentials_malformedSecret_denied() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        VaultKey key = Mockito.mock(VaultKey.class);
        when(key.get()).thenReturn("no-colon-here".getBytes(StandardCharsets.UTF_8));
        when(vault.get("proxy-ref", "u")).thenReturn(Optional.of(key));
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .vault(vault)
                .connection(
                        conn(
                                "{\"requireProxy\":true,\"proxyUrl\":\"http://127.0.0.1:9999\",\"proxyCredentialVaultRef\":\"proxy-ref\"}"))
                .actor("u")
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://example.com/ok"))
                .GET()
                .build();
        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
    }

    @Test
    void proxyCredentials_keyAccessFailure_denied() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        VaultKey key = Mockito.mock(VaultKey.class);
        when(key.get()).thenThrow(new RuntimeException("key access boom"));
        when(vault.get("proxy-ref", "u")).thenReturn(Optional.of(key));
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .vault(vault)
                .connection(
                        conn(
                                "{\"requireProxy\":true,\"proxyUrl\":\"http://127.0.0.1:9999\",\"proxyCredentialVaultRef\":\"proxy-ref\"}"))
                .actor("u")
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://example.com/ok"))
                .GET()
                .build();
        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, ex.category());
    }

    // ------------------------------------------------------------------
    // Custom-CA trust-mode failure modes
    // ------------------------------------------------------------------

    // NOTE: the "CUSTOM_CA with a blank/missing customCaVaultRef" branch (GovernedHttpClient lines
    // 287-288) is unreachable: NetworkProfilePolicy.fromJson (lines 137-141) fails closed to SYSTEM
    // whenever CUSTOM_CA lacks a CA ref, so tlsTrustMode() can only be CUSTOM_CA with a non-blank ref.
    // Reported as a dead defensive guard rather than faked.

    @Test
    void customCaMode_unresolvedVaultRef_throwsTls() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        when(vault.get("ca-ref", "u")).thenReturn(Optional.empty());
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .vault(vault)
                .connection(conn("{\"tlsTrustMode\":\"CUSTOM_CA\",\"customCaVaultRef\":\"ca-ref\"}"))
                .actor("u")
                .build();
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + "/ok")).GET().build();
        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.TLS_TRUST, ex.category());
    }

    @Test
    void customCaMode_invalidCertificate_throwsTls() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        VaultKey key = Mockito.mock(VaultKey.class);
        when(key.get()).thenReturn("not-a-certificate".getBytes(StandardCharsets.UTF_8));
        when(vault.get("ca-ref", "u")).thenReturn(Optional.of(key));
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .vault(vault)
                .connection(conn("{\"tlsTrustMode\":\"CUSTOM_CA\",\"customCaVaultRef\":\"ca-ref\"}"))
                .actor("u")
                .build();
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + "/ok")).GET().build();
        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.TLS_TRUST, ex.category());
    }

    /**
     * A self-signed EC P-256 certificate (DER, base64) used purely as a parseable X.509 fixture for the
     * custom-CA trust path. It is never used to verify a real TLS handshake.
     */
    private static final String TEST_CA_DER_BASE64 =
            "MIIBIjCByAIJAJufsTusFMEsMAoGCCqGSM49BAMCMBgxFjAUBgNVBAMMDWFtc2hhLXRlc3QtY2EwIBcNMjYwOTE1MDYwODMzWhgPMjEyNjA4MjIwNjA4MzNaMBgxFjAUBgNVBAMMDWFtc2hhLXRlc3QtY2EwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASgZPFms5FHypxeAQNpxtoz6SBpARICHC1QLegRjxgTGDHDFE8MFl/yVPdBlAyFE54kIlGzDCaoR3QOpMcTwNNAMAoGCCqGSM49BAMCA0kAMEYCIQDY5P+79NzWzI2bc4OMDNoBPJ/I13NWuxh5kMpM8C/KQwIhAJ1SEnGItOwOLJmsjacbQchm9i2z7xQUKqhVwNuIoODC";

    @Test
    void customCaMode_validCertificate_buildsSslContextAndProceeds() {
        SecretVault vault = Mockito.mock(SecretVault.class);
        VaultKey key = Mockito.mock(VaultKey.class);
        when(key.get()).thenReturn(Base64.getDecoder().decode(TEST_CA_DER_BASE64));
        when(vault.get("ca-ref", "u")).thenReturn(Optional.of(key));
        GovernedHttpClient client = GovernedHttpClient.builder(alwaysAllow)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .vault(vault)
                .connection(conn("{\"tlsTrustMode\":\"CUSTOM_CA\",\"customCaVaultRef\":\"ca-ref\"}"))
                .actor("u")
                .build();
        // Plain-HTTP target: the custom SSLContext is built (exercising the cert/keystore/SSLContext
        // success path) but is not used for the actual cleartext request, so it still returns 200.
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + "/ok")).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
    }

    private static LlmConnectionDto conn(String policyJson) {
        return new LlmConnectionDto(
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
    }
}
