package com.amsha.llm.providers.helper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.llm.api.oauth.CodexOAuthTokenExtractor;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;
import com.amsha.security.http.ApprovalReceiptIssuer;
import com.amsha.security.http.OutboundApprovalReceipt;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CodexTokenResolverTest {

    private CodexOAuthTokenExtractor tokenExtractor;
    private SecretVault secretVault;
    private ApprovalReceiptIssuer approvalReceiptIssuer;
    private CodexTokenResolver resolver;

    @BeforeEach
    void setUp() {
        tokenExtractor = Mockito.mock(CodexOAuthTokenExtractor.class);
        secretVault = Mockito.mock(SecretVault.class);
        approvalReceiptIssuer = Mockito.mock(ApprovalReceiptIssuer.class);
        resolver = new CodexTokenResolver();
        resolver.tokenExtractor = tokenExtractor;
        resolver.secretVault = secretVault;
        resolver.approvalReceiptIssuer = approvalReceiptIssuer;
    }

    @Test
    void resolveToken_returnsExplicitKeyWhenProvided() {
        String token = resolver.resolveToken(Optional.of("explicit-api-key"));
        assertEquals("explicit-api-key", token);
        verify(secretVault, never()).retrieveSystemKey(any());
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void resolveToken_returnsVaultKeyWhenPresent() {
        when(secretVault.retrieveSystemKey(VaultConstants.CODEX_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.of(new VaultKey("vault-token".getBytes(StandardCharsets.UTF_8))));

        String token = resolver.resolveToken();
        assertEquals("vault-token", token);
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void resolveToken_extractsAndStoresInVaultWhenNotCached() {
        when(secretVault.retrieveSystemKey(VaultConstants.CODEX_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.empty());
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("extracted-oauth-token"));

        AtomicReference<byte[]> stored = new AtomicReference<>();
        Mockito.doAnswer(inv -> {
                    stored.set(((byte[]) inv.getArgument(1)).clone());
                    return null;
                })
                .when(secretVault)
                .storeSystemKey(any(), any());

        String token = resolver.resolveToken();
        assertEquals("extracted-oauth-token", token);
        Assertions.assertArrayEquals("extracted-oauth-token".getBytes(StandardCharsets.UTF_8), stored.get());
        verify(secretVault).storeSystemKey(eq(VaultConstants.CODEX_OAUTH_TOKEN_KEY), any(byte[].class));
    }

    @Test
    void resolveToken_throwsWhenNoTokenAvailable() {
        when(secretVault.retrieveSystemKey(VaultConstants.CODEX_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.empty());
        when(tokenExtractor.extractToken()).thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> resolver.resolveToken());
        assertEquals(
                "Codex provider requires an active login. Please run 'codex login' in your local terminal first, or set AMSHA_CODEX_API_KEY.",
                ex.getMessage());
    }

    @Test
    void resolveTokenWithGrant_success() {
        when(approvalReceiptIssuer.verifyAndConsume(
                        "grant-xyz", OutboundPurpose.PROVIDER_OAUTH_EXCHANGE, "chatgpt.com"))
                .thenReturn(receiptFor("user-1", "sess-1"));
        when(secretVault.retrieveSystemKey(VaultConstants.CODEX_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.empty());
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("grant-extracted-token"));

        AtomicReference<byte[]> stored = new AtomicReference<>();
        Mockito.doAnswer(inv -> {
                    stored.set(((byte[]) inv.getArgument(1)).clone());
                    return null;
                })
                .when(secretVault)
                .storeSystemKey(eq(VaultConstants.CODEX_OAUTH_TOKEN_KEY), any(byte[].class));

        String token = resolver.resolveTokenWithGrant("user-1", "sess-1", "grant-xyz");
        assertEquals("grant-extracted-token", token);
        assertArrayEquals("grant-extracted-token".getBytes(StandardCharsets.UTF_8), stored.get());
        verify(approvalReceiptIssuer)
                .verifyAndConsume("grant-xyz", OutboundPurpose.PROVIDER_OAUTH_EXCHANGE, "chatgpt.com");
    }

    @Test
    void resolveTokenWithGrant_invalidGrant_throws() {
        when(approvalReceiptIssuer.verifyAndConsume(
                        "grant-bad", OutboundPurpose.PROVIDER_OAUTH_EXCHANGE, "chatgpt.com"))
                .thenReturn(null);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> resolver.resolveTokenWithGrant("user-1", "sess-1", "grant-bad"));
        assertTrue(ex.getMessage().contains("invalid, expired, or already consumed"));
    }

    @Test
    void resolveTokenWithGrant_actorMismatch_throws() {
        when(approvalReceiptIssuer.verifyAndConsume(
                        "grant-xyz", OutboundPurpose.PROVIDER_OAUTH_EXCHANGE, "chatgpt.com"))
                .thenReturn(receiptFor("other-user", "sess-1"));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> resolver.resolveTokenWithGrant("user-1", "sess-1", "grant-xyz"));
        assertTrue(ex.getMessage().contains("actor mismatch"));
    }

    @Test
    void resolveTokenWithGrant_sessionMismatch_throws() {
        when(approvalReceiptIssuer.verifyAndConsume(
                        "grant-xyz", OutboundPurpose.PROVIDER_OAUTH_EXCHANGE, "chatgpt.com"))
                .thenReturn(receiptFor("user-1", "other-session"));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> resolver.resolveTokenWithGrant("user-1", "sess-1", "grant-xyz"));

        assertTrue(ex.getMessage().contains("session mismatch"));
    }

    @Test
    void resolveTokenWithGrant_missingActor_throws() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> resolver.resolveTokenWithGrant(null, "sess-1", "grant-xyz"));
        assertTrue(ex.getMessage().contains("Actor user ID is required"));
    }

    @Test
    void resolveTokenWithGrant_missingSession_throws() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> resolver.resolveTokenWithGrant("user-1", "   ", "grant-xyz"));
        assertTrue(ex.getMessage().contains("Session ID is required"));
    }

    @Test
    void resolveTokenWithGrant_missingToken_throws() {
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> resolver.resolveTokenWithGrant("user-1", "sess-1", ""));
        assertTrue(ex.getMessage().contains("approvalToken is required"));
    }

    @Test
    void resolveTokenWithGrant_withoutAnIssuer_throws() {
        CodexTokenResolver withoutIssuer = new CodexTokenResolver();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> withoutIssuer.resolveTokenWithGrant("user-1", "sess-1", "grant-1"));
        assertTrue(ex.getMessage().contains("ApprovalReceiptIssuer is unavailable"));
    }

    @Test
    void resolveTokenWithGrant_returnsTheVaultTokenWhenItIsAlreadyCached() {
        when(approvalReceiptIssuer.verifyAndConsume(
                        "grant-xyz", OutboundPurpose.PROVIDER_OAUTH_EXCHANGE, "chatgpt.com"))
                .thenReturn(receiptFor("user-1", "sess-1"));
        when(secretVault.retrieveSystemKey(VaultConstants.CODEX_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.of(new VaultKey("cached-grant-token".getBytes(StandardCharsets.UTF_8))));

        assertEquals("cached-grant-token", resolver.resolveTokenWithGrant("user-1", "sess-1", "grant-xyz"));
        // A cached vault token short-circuits the extractor and the re-store.
        verify(tokenExtractor, never()).extractToken();
        verify(secretVault, never()).storeSystemKey(any(), any());
    }

    @Test
    void resolveTokenWithGrant_throwsWhenNothingRemainsAfterGrantVerification() {
        when(approvalReceiptIssuer.verifyAndConsume(
                        "grant-xyz", OutboundPurpose.PROVIDER_OAUTH_EXCHANGE, "chatgpt.com"))
                .thenReturn(receiptFor("user-1", "sess-1"));
        when(secretVault.retrieveSystemKey(VaultConstants.CODEX_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.empty());
        when(tokenExtractor.extractToken()).thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> resolver.resolveTokenWithGrant("user-1", "sess-1", "grant-xyz"));
        assertTrue(ex.getMessage().contains("Codex credentials not found after grant verification"));
    }

    @Test
    void resolveIssuer_prefersTheDirectlySetField() {
        assertSame(approvalReceiptIssuer, resolver.resolveIssuer());
    }

    @Test
    void resolveIssuer_fallsBackToTheInjectedInstance() {
        CodexTokenResolver withInstance = new CodexTokenResolver();
        ApprovalReceiptIssuer fromCdi = Mockito.mock(ApprovalReceiptIssuer.class);
        withInstance.approvalReceiptIssuerInstance = resolvable(fromCdi);

        assertSame(fromCdi, withInstance.resolveIssuer());
    }

    @Test
    void resolveIssuer_isNullWhenTheInjectedInstanceIsNotResolvable() {
        CodexTokenResolver withInstance = new CodexTokenResolver();
        withInstance.approvalReceiptIssuerInstance = unresolvable();

        assertNull(withInstance.resolveIssuer());
    }

    @Test
    void resolveAccountId_returnsWhatTheExtractorReports() {
        when(tokenExtractor.extractAccountId()).thenReturn(Optional.of("acct-42"));

        assertEquals(Optional.of("acct-42"), resolver.resolveAccountId());
    }

    @Test
    void resolveAccountId_isEmptyWithoutAnExtractor() {
        assertEquals(Optional.empty(), new CodexTokenResolver().resolveAccountId());
    }

    private static OutboundApprovalReceipt receiptFor(String actorUserId, String sessionId) {
        return new OutboundApprovalReceipt(
                actorUserId,
                ApprovalReceiptIssuer.oauthExchangeSessionId("codex", sessionId),
                OutboundPurpose.PROVIDER_OAUTH_EXCHANGE,
                "chatgpt.com");
    }

    private static <T> Instance<T> resolvable(T value) {
        @SuppressWarnings("unchecked")
        Instance<T> instance = Mockito.mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    private static <T> Instance<T> unresolvable() {
        @SuppressWarnings("unchecked")
        Instance<T> instance = Mockito.mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return instance;
    }
}
