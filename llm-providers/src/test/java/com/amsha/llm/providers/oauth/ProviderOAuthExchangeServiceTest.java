package com.amsha.llm.providers.oauth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
