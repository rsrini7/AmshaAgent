package com.amsha.llm.providers.oauth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.http.ApprovalReceiptIssuer;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundApprovalReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ProviderOAuthExchangeServiceTest {

    private GovernedTransportService transport;
    private SecretVault vault;
    private AuditService auditService;
    private ApprovalReceiptIssuer approvalIssuer;
    private ProviderOAuthExchangeService service;

    @BeforeEach
    void setUp() {
        transport = mock(GovernedTransportService.class);
        vault = mock(SecretVault.class);
        auditService = mock(AuditService.class);
        approvalIssuer = mock(ApprovalReceiptIssuer.class);

        service = new ProviderOAuthExchangeService();
        service.governedTransport = transport;
        service.secretVault = vault;
        service.auditService = auditService;
        service.approvalReceiptIssuer = approvalIssuer;
        service.pkceStore = new ProviderOAuthPkceStore();
        service.objectMapper = new ObjectMapper();
    }

    @Test
    void exchangeOrRefreshToken_success() {
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body())
                .thenReturn(
                        "{\"access_token\":\"secret-access-tok-123\",\"refresh_token\":\"secret-refresh-tok-456\",\"expires_in\":3600}");

        when(transport.<String>send(
                        eq("https://auth0.openai.com/oauth/token"),
                        eq(OutboundPurpose.PROVIDER_OAUTH_EXCHANGE),
                        any(),
                        eq("user-1"),
                        eq(false),
                        eq("grant-tok-123"),
                        any(HttpRequest.class),
                        any()))
                .thenReturn(mockResponse);

        AtomicReference<byte[]> storedAccess = new AtomicReference<>();
        AtomicReference<byte[]> storedRefresh = new AtomicReference<>();
        Mockito.doAnswer(inv -> {
                    String key = inv.getArgument(0);
                    byte[] bytes = inv.getArgument(1);
                    if (VaultConstants.CODEX_OAUTH_TOKEN_KEY.equals(key)) {
                        storedAccess.set(bytes.clone());
                    } else if ((VaultConstants.CODEX_OAUTH_TOKEN_KEY + "-refresh").equals(key)) {
                        storedRefresh.set(bytes.clone());
                    }
                    return null;
                })
                .when(vault)
                .storeSystemKey(anyString(), any(byte[].class));

        ProviderOAuthResult result = service.exchangeOrRefreshToken(
                "codex",
                "https://auth0.openai.com/oauth/token",
                Map.of("grant_type", "refresh_token", "client_id", "my-client"),
                "user-1",
                "sess-1",
                "grant-tok-123");

        assertTrue(result.success());
        assertNull(result.error());
        assertEquals(3600, result.expiresInSeconds());

        assertArrayEquals("secret-access-tok-123".getBytes(StandardCharsets.UTF_8), storedAccess.get());
        assertArrayEquals("secret-refresh-tok-456".getBytes(StandardCharsets.UTF_8), storedRefresh.get());

        verify(auditService)
                .recordMandatory(
                        eq("sess-1"),
                        eq("user-1"),
                        eq("system"),
                        eq(SecurityEventType.SETTINGS_CHANGED),
                        eq("oauth_exchange_succeeded provider=codex"));
    }

    @Test
    void exchangeOrRefreshToken_missingApprovalToken_fails() {
        ProviderOAuthResult result = service.exchangeOrRefreshToken(
                "codex", "https://auth0.openai.com/oauth/token", Map.of(), "user-1", "sess-1", null);

        assertFalse(result.success());
        assertTrue(result.error().contains("approvalToken is required"));
        verify(transport, never())
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());
    }

    @Test
    void exchangeOrRefreshToken_httpError_failsWithoutLeakingSecrets() {
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(401);
        when(mockResponse.body()).thenReturn("{\"error\":\"invalid_client\"}");

        when(transport.<String>send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any()))
                .thenReturn(mockResponse);

        ProviderOAuthResult result = service.exchangeOrRefreshToken(
                "codex",
                "https://auth0.openai.com/oauth/token",
                Map.of("grant_type", "refresh_token"),
                "user-1",
                "sess-1",
                "grant-tok-123");

        assertFalse(result.success());
        assertTrue(result.error().contains("HTTP 401"));
        assertFalse(result.error().contains("secret"));
        verify(vault, never()).storeSystemKey(anyString(), any());
        verify(auditService)
                .recordMandatory(
                        eq("sess-1"),
                        eq("user-1"),
                        eq("system"),
                        eq(SecurityEventType.UNAUTHORIZED_ACCESS),
                        eq("oauth_exchange_failed provider=codex status=401"));
    }

    @Test
    void storeExtractedTokenWithGrant_success() {
        when(approvalIssuer.verifyAndConsume("grant-456", OutboundPurpose.PROVIDER_OAUTH_EXCHANGE, "api.anthropic.com"))
                .thenReturn(new OutboundApprovalReceipt(
                        "user-1",
                        "oauth-exchange:claude-code:sess-1",
                        OutboundPurpose.PROVIDER_OAUTH_EXCHANGE,
                        "api.anthropic.com"));

        AtomicReference<byte[]> stored = new AtomicReference<>();
        Mockito.doAnswer(inv -> {
                    byte[] bytes = inv.getArgument(1);
                    stored.set(bytes.clone());
                    return null;
                })
                .when(vault)
                .storeSystemKey(eq(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY), any(byte[].class));

        boolean ok = service.storeExtractedTokenWithGrant(
                "claude-code", "extracted-claude-token", "user-1", "sess-1", "grant-456");

        assertTrue(ok);
        assertArrayEquals("extracted-claude-token".getBytes(StandardCharsets.UTF_8), stored.get());
        verify(auditService)
                .recordMandatory(
                        eq("sess-1"),
                        eq("user-1"),
                        eq("system"),
                        eq(SecurityEventType.SETTINGS_CHANGED),
                        eq("oauth_token_stored_with_grant provider=claude-code"));
    }

    @Test
    void storeExtractedTokenWithGrant_actorMismatch_rejected() {
        when(approvalIssuer.verifyAndConsume("grant-456", OutboundPurpose.PROVIDER_OAUTH_EXCHANGE, "api.anthropic.com"))
                .thenReturn(new OutboundApprovalReceipt(
                        "other-user",
                        "oauth-exchange:claude-code:sess-1",
                        OutboundPurpose.PROVIDER_OAUTH_EXCHANGE,
                        "api.anthropic.com"));

        boolean ok = service.storeExtractedTokenWithGrant(
                "claude-code", "extracted-claude-token", "user-1", "sess-1", "grant-456");

        assertFalse(ok);
        verify(vault, never()).storeSystemKey(anyString(), any());
    }

    @Test
    void storeExtractedTokenWithGrant_sessionMismatch_rejected() {
        when(approvalIssuer.verifyAndConsume("grant-456", OutboundPurpose.PROVIDER_OAUTH_EXCHANGE, "api.anthropic.com"))
                .thenReturn(new OutboundApprovalReceipt(
                        "user-1",
                        "oauth-exchange:claude-code:other-session",
                        OutboundPurpose.PROVIDER_OAUTH_EXCHANGE,
                        "api.anthropic.com"));

        boolean ok = service.storeExtractedTokenWithGrant(
                "claude-code", "extracted-claude-token", "user-1", "sess-1", "grant-456");

        assertFalse(ok);
        verify(vault, never()).storeSystemKey(anyString(), any());
    }

    @Test
    void resolveVaultKey_mappings() {
        assertEquals(VaultConstants.CODEX_OAUTH_TOKEN_KEY, ProviderOAuthExchangeService.resolveVaultKey("codex"));
        assertEquals(
                VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY,
                ProviderOAuthExchangeService.resolveVaultKey("claude-code"));
        assertEquals(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY, ProviderOAuthExchangeService.resolveVaultKey("kimchi"));
        assertEquals(VaultConstants.AGY_OAUTH_TOKEN_KEY, ProviderOAuthExchangeService.resolveVaultKey("agy"));
        assertEquals("generic-oauth-token", ProviderOAuthExchangeService.resolveVaultKey(null));
    }

    @Test
    void generatePkceParams_createsValidParameters() {
        var params = ProviderOAuthExchangeService.generatePkceParams();
        assertFalse(params.codeVerifier().isBlank());
        assertFalse(params.codeChallenge().isBlank());
        assertFalse(params.state().isBlank());
    }

    @Test
    void exchangeOrRefreshToken_missingSession_fails() {
        ProviderOAuthResult result = service.exchangeOrRefreshToken(
                "codex",
                "https://auth0.openai.com/oauth/token",
                Map.of("grant_type", "refresh_token"),
                "user-1",
                null,
                "grant-tok-123");

        assertFalse(result.success());
        assertTrue(result.error().contains("Session ID is required"));
        verify(transport, never())
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());
    }

    @Test
    void exchangeOrRefreshToken_authorizationCodeWithoutVerifier_rejected() {
        ProviderOAuthResult result = service.exchangeOrRefreshToken(
                "codex",
                "https://auth0.openai.com/oauth/token",
                Map.of("grant_type", "authorization_code", "code", "auth-code-123"),
                "user-1",
                "sess-1",
                "grant-tok-123");

        assertFalse(result.success());
        assertTrue(result.error().contains("code_verifier"));
        verify(transport, never())
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());
    }

    @Test
    void exchangeOrRefreshToken_authorizationCodeWithUnknownState_rejected() {
        ProviderOAuthResult result = service.exchangeOrRefreshToken(
                "codex",
                "https://auth0.openai.com/oauth/token",
                Map.of("grant_type", "authorization_code", "code", "auth-code-123"),
                null,
                "user-1",
                "sess-1",
                "grant-tok-123",
                "some-verifier",
                "unknown-state");

        assertFalse(result.success());
        assertTrue(result.error().contains("PKCE state"));
        verify(transport, never())
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());
    }

    @Test
    void exchangeOrRefreshToken_authorizationCodeWithValidPkce_succeedsAndConsumesState() {
        ProviderOAuthExchangeService.PkceParameters pkce = service.beginAuthorization("codex", "user-1", "sess-1");
        assertTrue(service.validateCallbackState("codex", pkce.state(), "user-1", "sess-1"));

        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"access_token\":\"pkce-access-token\",\"expires_in\":3600}");
        AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();
        when(transport.<String>send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    capturedRequest.set(invocation.getArgument(6));
                    return mockResponse;
                });

        ProviderOAuthResult result = service.exchangeOrRefreshToken(
                "codex",
                "https://auth0.openai.com/oauth/token",
                Map.of("grant_type", "authorization_code", "code", "auth-code-123"),
                null,
                "user-1",
                "sess-1",
                "grant-tok-123",
                pkce.codeVerifier(),
                pkce.state());

        assertTrue(result.success());
        assertFalse(service.validateCallbackState("codex", pkce.state(), "user-1", "sess-1"));
        String expectedBody = "grant_type=authorization_code&code=auth-code-123&code_verifier=" + pkce.codeVerifier();
        assertTrue(capturedRequest.get().bodyPublisher().isPresent());
        assertEquals(
                expectedBody.getBytes(StandardCharsets.UTF_8).length,
                capturedRequest.get().bodyPublisher().orElseThrow().contentLength());
        verify(vault).storeSystemKey(eq(VaultConstants.CODEX_OAUTH_TOKEN_KEY), any(byte[].class));
    }

    @Test
    void exchangeOrRefreshToken_authorizationCodeWithWrongVerifier_rejected() {
        ProviderOAuthExchangeService.PkceParameters pkce = service.beginAuthorization("codex", "user-1", "sess-1");

        ProviderOAuthResult result = service.exchangeOrRefreshToken(
                "codex",
                "https://auth0.openai.com/oauth/token",
                Map.of("grant_type", "authorization_code", "code", "auth-code-123"),
                null,
                "user-1",
                "sess-1",
                "grant-tok-123",
                "tampered-verifier-value",
                pkce.state());

        assertFalse(result.success());
        assertTrue(result.error().contains("code_verifier"));
        verify(transport, never())
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());
    }

    @Test
    void exchangeOrRefreshToken_rejectsConflictingFormVerifier() {
        ProviderOAuthExchangeService.PkceParameters pkce = service.beginAuthorization("codex", "user-1", "sess-1");

        ProviderOAuthResult result = service.exchangeOrRefreshToken(
                "codex",
                "https://auth0.openai.com/oauth/token",
                Map.of(
                        "grant_type", "authorization_code",
                        "code", "auth-code-123",
                        "code_verifier", "different-verifier"),
                null,
                "user-1",
                "sess-1",
                "grant-tok-123",
                pkce.codeVerifier(),
                pkce.state());

        assertFalse(result.success());
        assertTrue(result.error().contains("does not match"));
        verify(transport, never())
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());
    }

    @Test
    void exchangeOrRefreshToken_releasesPkceClaimAfterHttpError() {
        ProviderOAuthExchangeService.PkceParameters pkce = service.beginAuthorization("codex", "user-1", "sess-1");
        @SuppressWarnings("unchecked")
        HttpResponse<String> failedResponse = mock(HttpResponse.class);
        when(failedResponse.statusCode()).thenReturn(401);
        when(failedResponse.body()).thenReturn("{\"error\":\"invalid_client\"}");
        when(transport.<String>send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any()))
                .thenReturn(failedResponse);

        ProviderOAuthResult failed = service.exchangeOrRefreshToken(
                "codex",
                "https://auth0.openai.com/oauth/token",
                Map.of("grant_type", "authorization_code", "code", "auth-code"),
                null,
                "user-1",
                "sess-1",
                "grant-tok-123",
                pkce.codeVerifier(),
                pkce.state());

        assertFalse(failed.success());
        assertTrue(failed.error().contains("HTTP 401"));

        @SuppressWarnings("unchecked")
        HttpResponse<String> successResponse = mock(HttpResponse.class);
        when(successResponse.statusCode()).thenReturn(200);
        when(successResponse.body()).thenReturn("{\"access_token\":\"retry-token\"}");
        when(transport.<String>send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any()))
                .thenReturn(successResponse);

        ProviderOAuthResult retry = service.exchangeOrRefreshToken(
                "codex",
                "https://auth0.openai.com/oauth/token",
                Map.of("grant_type", "authorization_code", "code", "auth-code"),
                null,
                "user-1",
                "sess-1",
                "grant-tok-123",
                pkce.codeVerifier(),
                pkce.state());

        assertTrue(retry.success());
    }

    @Test
    void validateCallbackState_rejectsBindingMismatch() {
        ProviderOAuthExchangeService.PkceParameters pkce = service.beginAuthorization("codex", "user-1", "sess-1");

        assertFalse(service.validateCallbackState("codex", pkce.state(), "other-user", "sess-1"));
        assertFalse(service.validateCallbackState("codex", pkce.state(), "user-1", "other-session"));
        assertFalse(service.validateCallbackState("kimchi", pkce.state(), "user-1", "sess-1"));
    }

    @Test
    void beginAuthorization_rejectsMissingSession() {
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.beginAuthorization("codex", "user-1", null));
    }

    // ---------------------------------------------------------------------------------------------
    // Collaborator resolution ladder. The service carries both a directly-assigned field (used by
    // tests and by callers that inject by hand) and a CDI Instance fallback, so every resolver has
    // three rungs: field, resolvable instance, and a last-resort default. The happy-path tests above
    // only ever exercise the first rung.
    // ---------------------------------------------------------------------------------------------

    @Test
    void resolvers_fallBackToTheCdiInstanceWhenTheFieldIsUnset() {
        ProviderOAuthExchangeService bare = new ProviderOAuthExchangeService();
        GovernedTransportService cdiTransport = mock(GovernedTransportService.class);
        SecretVault cdiVault = mock(SecretVault.class);
        AuditService cdiAudit = mock(AuditService.class);
        ApprovalReceiptIssuer cdiIssuer = mock(ApprovalReceiptIssuer.class);
        ProviderOAuthPkceStore cdiPkceStore = new ProviderOAuthPkceStore();

        bare.governedTransportInstance = resolvable(cdiTransport);
        bare.secretVaultInstance = resolvable(cdiVault);
        bare.auditServiceInstance = resolvable(cdiAudit);
        bare.objectMapperInstance = resolvable(new ObjectMapper());
        bare.approvalReceiptIssuerInstance = resolvable(cdiIssuer);
        bare.pkceStoreInstance = resolvable(cdiPkceStore);

        assertSame(cdiTransport, bare.resolveTransport());
        assertSame(cdiVault, bare.resolveVault());
        assertSame(cdiAudit, bare.resolveAudit());
        assertNotNull(bare.resolveMapper());
        assertSame(cdiIssuer, bare.resolveIssuer());
        assertSame(cdiPkceStore, bare.resolvePkceStore());
    }

    @Test
    void resolvers_returnNullWhenNeitherAFieldNorAResolvableInstanceIsPresent() {
        ProviderOAuthExchangeService bare = new ProviderOAuthExchangeService();
        bare.governedTransportInstance = unresolvable();
        bare.secretVaultInstance = unresolvable();
        bare.auditServiceInstance = unresolvable();
        bare.objectMapperInstance = unresolvable();
        bare.approvalReceiptIssuerInstance = unresolvable();
        bare.pkceStoreInstance = unresolvable();

        assertNull(bare.resolveTransport());
        assertNull(bare.resolveVault());
        assertNull(bare.resolveAudit());
        assertNull(bare.resolveIssuer());
        // The mapper is the one resolver with a usable last-resort default rather than null.
        assertNotNull(bare.resolveMapper());
    }

    @Test
    void resolvePkceStore_createsAndCachesAStoreWhenNoneIsAvailable() {
        ProviderOAuthExchangeService bare = new ProviderOAuthExchangeService();

        ProviderOAuthPkceStore created = bare.resolvePkceStore();

        assertNotNull(created);
        assertSame(created, bare.resolvePkceStore(), "the fallback store must be cached on the field");
    }

    // ---------------------------------------------------------------------------------------------
    // Argument validation and degraded transport.
    // ---------------------------------------------------------------------------------------------

    @Test
    void exchangeOrRefreshToken_requiresProviderEndpointAndActor() {
        assertTrue(rejection(null, "https://auth.openai.com/token", "user-1", "sess-1", "grant-tok")
                .contains("Provider ID"));
        assertTrue(rejection("  ", "https://auth.openai.com/token", "user-1", "sess-1", "grant-tok")
                .contains("Provider ID"));
        assertTrue(rejection("codex", null, "user-1", "sess-1", "grant-tok").contains("Token endpoint"));
        assertTrue(rejection("codex", "  ", "user-1", "sess-1", "grant-tok").contains("Token endpoint"));
        assertTrue(rejection("codex", "https://auth.openai.com/token", null, "sess-1", "grant-tok")
                .contains("Actor user ID"));
        assertTrue(rejection("codex", "https://auth.openai.com/token", "  ", "sess-1", "grant-tok")
                .contains("Actor user ID"));
    }

    @Test
    void exchangeOrRefreshToken_withoutAGovenedTransportFailsAndReleasesTheClaim() {
        ProviderOAuthExchangeService bare = new ProviderOAuthExchangeService();
        bare.pkceStore = new ProviderOAuthPkceStore();

        ProviderOAuthResult result = bare.exchangeOrRefreshToken(
                "codex",
                "https://auth.openai.com/token",
                Map.of("grant_type", "refresh_token"),
                "user-1",
                "sess-1",
                "grant-tok");

        assertFalse(result.success());
        assertTrue(result.error().contains("GovernedTransportService unavailable"));
    }

    @Test
    void exchangeOrRefreshToken_withNoFormParametersSendsAnEmptyBody() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        when(transport.<String>send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    captured.set(invocation.getArgument(6));
                    return tokenResponse(200, "{\"access_token\":\"tok\"}");
                });

        ProviderOAuthResult result = service.exchangeOrRefreshToken(
                "codex", "https://auth.openai.com/token", Map.of(), "user-1", "sess-1", "grant-tok");

        assertTrue(result.success());
        assertEquals(
                0,
                captured.get().bodyPublisher().orElseThrow().contentLength(),
                "an empty parameter map must serialise to a zero-length form body");
    }

    // ---------------------------------------------------------------------------------------------
    // Token response parsing. Each arm below returns a 200 so the only variable is the payload.
    // ---------------------------------------------------------------------------------------------

    @Test
    void exchangeOrRefreshToken_rejectsAnEmptyTokenResponse() {
        doReturn(tokenResponse(200, null))
                .when(transport)
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());
        assertTrue(refresh().error().contains("Empty token response"));

        doReturn(tokenResponse(200, "   "))
                .when(transport)
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());
        assertTrue(refresh().error().contains("Empty token response"));
    }

    @Test
    void exchangeOrRefreshToken_readsTheCamelCaseExpiresInField() {
        doReturn(tokenResponse(200, "{\"access_token\":\"tok\",\"expiresIn\":1200}"))
                .when(transport)
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());

        ProviderOAuthResult result = refresh();

        assertTrue(result.success());
        assertEquals(1200, result.expiresInSeconds());
    }

    @Test
    void exchangeOrRefreshToken_ignoresANonPositiveExpiry() {
        doReturn(tokenResponse(200, "{\"access_token\":\"tok\",\"expires_in\":0}"))
                .when(transport)
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());
        assertNull(refresh().expiresInSeconds());

        doReturn(tokenResponse(200, "{\"access_token\":\"tok\",\"expiresIn\":-5}"))
                .when(transport)
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());
        assertNull(refresh().expiresInSeconds());
    }

    @Test
    void exchangeOrRefreshToken_reportsAnUnparseableTokenResponse() {
        doReturn(tokenResponse(200, "{not-json"))
                .when(transport)
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());

        ProviderOAuthResult result = refresh();

        assertFalse(result.success());
        assertTrue(result.error().contains("Failed to parse OAuth token response"));
    }

    @Test
    void exchangeOrRefreshToken_releasesTheClaimWhenThePayloadCarriesNoAccessToken() {
        ProviderOAuthExchangeService.PkceParameters pkce = service.beginAuthorization("codex", "user-1", "sess-1");
        doReturn(tokenResponse(200, "{\"refresh_token\":\"only-a-refresh-token\"}"))
                .when(transport)
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());

        ProviderOAuthResult missing = exchangeWith(pkce, "auth-code-123");

        assertFalse(missing.success());
        assertTrue(missing.error().contains("Missing access_token"));

        doReturn(tokenResponse(200, "{\"access_token\":\"recovered\"}"))
                .when(transport)
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());

        ProviderOAuthResult retry = exchangeWith(pkce, "auth-code-123");

        assertTrue(retry.success(), "a failed exchange must release the in-flight PKCE claim for a retry");
    }

    // ---------------------------------------------------------------------------------------------
    // storeExtractedTokenWithGrant guards and static provider maps.
    // ---------------------------------------------------------------------------------------------

    @Test
    void storeExtractedTokenWithGrant_rejectsBlankArguments() {
        assertFalse(service.storeExtractedTokenWithGrant(null, "tok", "user-1", "sess-1", "grant"));
        assertFalse(service.storeExtractedTokenWithGrant("claude-code", "  ", "user-1", "sess-1", "grant"));
        assertFalse(service.storeExtractedTokenWithGrant("claude-code", "tok", null, "sess-1", "grant"));
        assertFalse(service.storeExtractedTokenWithGrant("claude-code", "tok", "user-1", " ", "grant"));
        assertFalse(service.storeExtractedTokenWithGrant("claude-code", "tok", "user-1", "sess-1", null));
    }

    @Test
    void storeExtractedTokenWithGrant_requiresBothAVaultAndAnIssuer() {
        ProviderOAuthExchangeService withoutVault = new ProviderOAuthExchangeService();
        withoutVault.approvalReceiptIssuer = approvalIssuer;
        assertFalse(withoutVault.storeExtractedTokenWithGrant("claude-code", "tok", "user-1", "sess-1", "grant"));

        ProviderOAuthExchangeService withoutIssuer = new ProviderOAuthExchangeService();
        withoutIssuer.secretVault = vault;
        assertFalse(withoutIssuer.storeExtractedTokenWithGrant("claude-code", "tok", "user-1", "sess-1", "grant"));
    }

    @Test
    void resolveVaultKey_fallsBackToAProviderScopedKeyForUnknownProviders() {
        assertEquals("custom-oauth-token", ProviderOAuthExchangeService.resolveVaultKey("custom"));
        assertEquals("custom-oauth-token", ProviderOAuthExchangeService.resolveVaultKey("  Custom  "));
        assertEquals(
                VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY,
                ProviderOAuthExchangeService.resolveVaultKey("claude_code"));
        assertEquals(VaultConstants.AGY_OAUTH_TOKEN_KEY, ProviderOAuthExchangeService.resolveVaultKey("antigravity"));
    }

    @Test
    void resolveTargetHost_mapsKnownProvidersAndDefaultsToLocalhost() {
        assertEquals("chatgpt.com", ProviderOAuthExchangeService.resolveTargetHost("codex"));
        assertEquals("api.anthropic.com", ProviderOAuthExchangeService.resolveTargetHost("claude-code"));
        assertEquals("api.anthropic.com", ProviderOAuthExchangeService.resolveTargetHost("claude_code"));
        assertEquals("generativelanguage.googleapis.com", ProviderOAuthExchangeService.resolveTargetHost("agy"));
        assertEquals(
                "generativelanguage.googleapis.com", ProviderOAuthExchangeService.resolveTargetHost("antigravity"));
        assertEquals("llm.kimchi.dev", ProviderOAuthExchangeService.resolveTargetHost("kimchi"));
        assertEquals("localhost", ProviderOAuthExchangeService.resolveTargetHost("some-unknown-provider"));
        assertEquals("localhost", ProviderOAuthExchangeService.resolveTargetHost(null));
    }

    @Test
    void computeS256Challenge_delegatesToThePkceStore() {
        String verifier = "a-fixed-verifier-value";

        assertEquals(
                ProviderOAuthPkceStore.computeS256Challenge(verifier),
                ProviderOAuthExchangeService.computeS256Challenge(verifier));
        assertNull(ProviderOAuthExchangeService.computeS256Challenge(null));
    }

    // ---------------------------------------------------------------------------------------------
    // Remaining reachable branches: the alternate spellings of each guard, and the vault-less /
    // audit-less / status-code edges.
    // ---------------------------------------------------------------------------------------------

    @Test
    void resolvers_returnNullWhenNoInstanceFieldIsPresentAtAll() {
        ProviderOAuthExchangeService bare = new ProviderOAuthExchangeService();

        assertNull(bare.resolveTransport());
        assertNull(bare.resolveVault());
        assertNull(bare.resolveAudit());
        assertNull(bare.resolveIssuer());
        assertNotNull(bare.resolveMapper());
        assertNotNull(bare.resolvePkceStore());
    }

    @Test
    void exchangeOrRefreshToken_requiresApprovalTokenAndSessionToBeNonBlank() {
        assertTrue(rejection("codex", "https://auth.openai.com/token", "user-1", "sess-1", "  ")
                .contains("approvalToken is required"));
        assertTrue(rejection("codex", "https://auth.openai.com/token", "user-1", "  ", "grant-tok")
                .contains("Session ID is required"));
    }

    @Test
    void exchangeOrRefreshToken_treatsNullFormParametersAsEmpty() {
        doReturn(tokenResponse(200, "{\"access_token\":\"tok\"}"))
                .when(transport)
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());

        ProviderOAuthResult result = service.exchangeOrRefreshToken(
                "codex", "https://auth.openai.com/token", null, "user-1", "sess-1", "grant-tok");

        assertTrue(result.success());
    }

    @Test
    void exchangeOrRefreshToken_acceptsAMatchingFormVerifier() {
        ProviderOAuthExchangeService.PkceParameters pkce = service.beginAuthorization("codex", "user-1", "sess-1");
        doReturn(tokenResponse(200, "{\"access_token\":\"tok\"}"))
                .when(transport)
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());

        ProviderOAuthResult result = service.exchangeOrRefreshToken(
                "codex",
                "https://auth.openai.com/token",
                Map.of(
                        "grant_type", "authorization_code",
                        "code", "auth-code-123",
                        "code_verifier", pkce.codeVerifier()),
                null,
                "user-1",
                "sess-1",
                "grant-tok-123",
                pkce.codeVerifier(),
                pkce.state());

        assertTrue(result.success());
    }

    @Test
    void exchangeOrRefreshToken_treatsASub200StatusAsAFailure() {
        doReturn(tokenResponse(100, ""))
                .when(transport)
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());

        ProviderOAuthResult result = refresh();

        assertFalse(result.success());
        assertTrue(result.error().contains("HTTP 100"));
    }

    @Test
    void exchangeOrRefreshToken_succeedsWithoutAVault() {
        ProviderOAuthExchangeService bare = new ProviderOAuthExchangeService();
        bare.governedTransport = transport;
        bare.pkceStore = new ProviderOAuthPkceStore();
        doReturn(tokenResponse(200, "{\"access_token\":\"tok\",\"refresh_token\":\"refresh\"}"))
                .when(transport)
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());

        ProviderOAuthResult result = bare.exchangeOrRefreshToken(
                "codex",
                "https://auth.openai.com/token",
                Map.of("grant_type", "refresh_token"),
                "user-1",
                "sess-1",
                "grant-tok");

        assertTrue(result.success(), "a missing vault must degrade to no-op storage, not a failure");
    }

    @Test
    void exchangeOrRefreshToken_fallsThroughABlankPrimaryTokenField() {
        doReturn(tokenResponse(200, "{\"access_token\":\"\",\"accessToken\":\"from-camel-case\"}"))
                .when(transport)
                .send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any());

        assertTrue(refresh().success(), "a blank snake_case field must fall through to the camelCase alias");
    }

    @Test
    void exchangeOrRefreshToken_survivesAMissingAuditServiceOnTheRejectionPath() {
        ProviderOAuthExchangeService bare = new ProviderOAuthExchangeService();
        bare.pkceStore = new ProviderOAuthPkceStore();

        ProviderOAuthResult result = bare.exchangeOrRefreshToken(
                "codex",
                "https://auth.openai.com/token",
                Map.of("grant_type", "authorization_code", "code", "auth-code"),
                "user-1",
                "sess-1",
                "grant-tok");

        assertFalse(result.success());
        assertTrue(result.error().contains("PKCE code_verifier is required"));
    }

    @Test
    void storeExtractedTokenWithGrant_rejectsBlankProviderActorAndApprovalToken() {
        assertFalse(service.storeExtractedTokenWithGrant("  ", "tok", "user-1", "sess-1", "grant"));
        assertFalse(service.storeExtractedTokenWithGrant("claude-code", "tok", "  ", "sess-1", "grant"));
        assertFalse(service.storeExtractedTokenWithGrant("claude-code", "tok", "user-1", "sess-1", "  "));
    }

    @Test
    void storeExtractedTokenWithGrant_rejectsAnUnverifiableReceipt() {
        when(approvalIssuer.verifyAndConsume(anyString(), any(), anyString())).thenReturn(null);

        assertFalse(service.storeExtractedTokenWithGrant("claude-code", "tok", "user-1", "sess-1", "grant"));
        verify(vault, never()).storeSystemKey(anyString(), any());
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static <T> Instance<T> resolvable(T value) {
        @SuppressWarnings("unchecked")
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    private static <T> Instance<T> unresolvable() {
        @SuppressWarnings("unchecked")
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return instance;
    }

    private static HttpResponse<String> tokenResponse(int status, String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    /** Runs a refresh-token exchange with the fully-wired service from {@link #setUp()}. */
    private ProviderOAuthResult refresh() {
        return service.exchangeOrRefreshToken(
                "codex",
                "https://auth.openai.com/token",
                Map.of("grant_type", "refresh_token"),
                "user-1",
                "sess-1",
                "grant-tok-123");
    }

    /** Runs an authorization-code exchange carrying the given PKCE binding. */
    private ProviderOAuthResult exchangeWith(ProviderOAuthExchangeService.PkceParameters pkce, String code) {
        return service.exchangeOrRefreshToken(
                "codex",
                "https://auth.openai.com/token",
                Map.of("grant_type", "authorization_code", "code", code),
                null,
                "user-1",
                "sess-1",
                "grant-tok-123",
                pkce.codeVerifier(),
                pkce.state());
    }

    /** Invokes the six-argument overload and returns the rejection message. */
    private String rejection(
            String providerId, String tokenEndpoint, String actorUserId, String sessionId, String approvalToken) {
        ProviderOAuthResult result = service.exchangeOrRefreshToken(
                providerId, tokenEndpoint, Map.of(), actorUserId, sessionId, approvalToken);
        assertFalse(result.success());
        return result.error();
    }
}
