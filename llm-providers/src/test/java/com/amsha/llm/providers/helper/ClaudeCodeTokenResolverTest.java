package com.amsha.llm.providers.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import com.amsha.common.config.ConfigKeys;
import com.amsha.llm.api.oauth.ClaudeCodeOAuthTokenExtractor;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;
import com.amsha.security.http.ApprovalReceiptIssuer;
import com.amsha.security.http.OutboundApprovalReceipt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link ClaudeCodeTokenResolver}, mirroring the {@link AgyTokenResolverTest} spec.
 *
 * <p>There is no CLI probe here, so nothing needs {@code mockStatic}. The fallback chain's property link
 * ({@code amsha.llm.claude-code.api-key}) is driven directly and its environment link
 * ({@code AMSHA_CLAUDE_CODE_API_KEY}) is guarded with an assumption, since a test cannot unset it.
 *
 * <p>One asymmetry worth noting: this resolver's tail re-queries the extractor
 * ({@code Optional.ofNullable(tokenExtractor).flatMap(...)}) rather than only consulting properties, so an
 * extractor that returned empty earlier is asked a second time.
 */
class ClaudeCodeTokenResolverTest {

    /** Property link of the fallback chain. */
    private static final String CLAUDE_CODE_PROPERTY = "amsha.llm.claude-code.api-key";

    /** Host the grant path must bind the approval receipt to. */
    private static final String TARGET_HOST = "api.anthropic.com";

    private static final String ACTOR = "user-1";
    private static final String SESSION = "session-1";
    private static final String GRANT_TOKEN = "grant-token";

    private ClaudeCodeOAuthTokenExtractor tokenExtractor;
    private SecretVault secretVault;
    private ClaudeCodeTokenResolver resolver;
    private String savedProperty;

    @BeforeEach
    void setUp() {
        tokenExtractor = Mockito.mock(ClaudeCodeOAuthTokenExtractor.class);
        secretVault = Mockito.mock(SecretVault.class);
        resolver = new ClaudeCodeTokenResolver();
        resolver.tokenExtractor = tokenExtractor;
        resolver.secretVault = secretVault;

        savedProperty = System.getProperty(CLAUDE_CODE_PROPERTY);
        System.clearProperty(CLAUDE_CODE_PROPERTY);
    }

    @AfterEach
    void tearDown() {
        if (savedProperty == null) {
            System.clearProperty(CLAUDE_CODE_PROPERTY);
        } else {
            System.setProperty(CLAUDE_CODE_PROPERTY, savedProperty);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void vaultHolds(String rawToken) {
        when(secretVault.retrieveSystemKey(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.of(new VaultKey(rawToken.getBytes(StandardCharsets.UTF_8))));
    }

    private void vaultEmpty() {
        when(secretVault.retrieveSystemKey(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.empty());
    }

    /** Captures what the resolver writes to the vault so the cached bytes can be asserted. */
    private AtomicReference<byte[]> captureVaultWrite() {
        AtomicReference<byte[]> stored = new AtomicReference<>();
        Mockito.doAnswer(inv -> {
                    stored.set(((byte[]) inv.getArgument(1)).clone());
                    return null;
                })
                .when(secretVault)
                .storeSystemKey(eq(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY), any(byte[].class));
        return stored;
    }

    private ApprovalReceiptIssuer issuerReturning(OutboundApprovalReceipt receipt) {
        ApprovalReceiptIssuer issuer = Mockito.mock(ApprovalReceiptIssuer.class);
        resolver.approvalReceiptIssuer = issuer;
        when(issuer.verifyAndConsume(eq(GRANT_TOKEN), eq(OutboundPurpose.PROVIDER_OAUTH_EXCHANGE), eq(TARGET_HOST)))
                .thenReturn(receipt);
        return issuer;
    }

    private static OutboundApprovalReceipt receipt(String actorUserId, String boundSessionId) {
        return new OutboundApprovalReceipt(
                actorUserId,
                ApprovalReceiptIssuer.oauthExchangeSessionId("claude-code", boundSessionId),
                OutboundPurpose.PROVIDER_OAUTH_EXCHANGE,
                TARGET_HOST);
    }

    @SuppressWarnings("unchecked")
    private static Instance<ApprovalReceiptIssuer> resolvableInstance(ApprovalReceiptIssuer value) {
        Instance<ApprovalReceiptIssuer> instance = Mockito.mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    @SuppressWarnings("unchecked")
    private static Instance<ApprovalReceiptIssuer> unresolvableInstance() {
        Instance<ApprovalReceiptIssuer> instance = Mockito.mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return instance;
    }

    // ------------------------------------------------------------- resolveIssuer()

    @Test
    void resolveIssuer_prefersTheDirectlyInjectedField() {
        ApprovalReceiptIssuer direct = Mockito.mock(ApprovalReceiptIssuer.class);
        resolver.approvalReceiptIssuer = direct;
        resolver.approvalReceiptIssuerInstance = resolvableInstance(Mockito.mock(ApprovalReceiptIssuer.class));
        assertSame(direct, resolver.resolveIssuer(), "the direct field must win over the CDI instance");
    }

    @Test
    void resolveIssuer_fallsBackToTheCdiInstance() {
        ApprovalReceiptIssuer fromCdi = Mockito.mock(ApprovalReceiptIssuer.class);
        resolver.approvalReceiptIssuer = null;
        resolver.approvalReceiptIssuerInstance = resolvableInstance(fromCdi);
        assertSame(fromCdi, resolver.resolveIssuer());
    }

    @Test
    void resolveIssuer_returnsNullWhenNothingIsAvailable() {
        resolver.approvalReceiptIssuer = null;
        resolver.approvalReceiptIssuerInstance = null;
        assertNull(resolver.resolveIssuer());
    }

    @Test
    void resolveIssuer_returnsNullWhenTheCdiInstanceIsNotResolvable() {
        resolver.approvalReceiptIssuer = null;
        resolver.approvalReceiptIssuerInstance = unresolvableInstance();
        assertNull(resolver.resolveIssuer());
    }

    // ------------------------------------------------ resolveToken(): precedence chain

    @Test
    void resolveToken_returnsExplicitKeyWhenProvided() {
        String token = resolver.resolveToken(Optional.of("explicit-api-key"));
        assertEquals("explicit-api-key", token);
        verify(secretVault, never()).retrieveSystemKey(any());
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void resolveToken_blankExplicitKeyFallsThroughToTheVault() {
        vaultHolds("vault-token");
        assertEquals("vault-token", resolver.resolveToken(Optional.of("   ")));
    }

    @Test
    void resolveToken_nullExplicitKeyFallsThroughToTheVault() {
        vaultHolds("vault-token");
        assertEquals("vault-token", resolver.resolveToken(null));
    }

    // ------------------------------------------------------ resolveToken(): vault path

    @Test
    void resolveToken_returnsVaultKeyWhenPresent() {
        when(secretVault.retrieveSystemKey(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.of(new VaultKey("vault-token".getBytes(StandardCharsets.UTF_8))));

        String token = resolver.resolveToken();
        assertEquals("vault-token", token);
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void resolveToken_trimsTheVaultToken() {
        vaultHolds("  vault-token  ");
        assertEquals("vault-token", resolver.resolveToken());
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void resolveToken_blankVaultTokenFallsThroughToTheExtractor() {
        vaultHolds("   ");
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("extracted-oauth-token"));
        assertEquals("extracted-oauth-token", resolver.resolveToken());
    }

    // -------------------------------------------------- resolveToken(): extractor path

    @Test
    void resolveToken_extractsAndStoresInVaultWhenNotCached() {
        when(secretVault.retrieveSystemKey(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY))
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
        assertEquals("extracted-oauth-token", new String(stored.get(), StandardCharsets.UTF_8));
        verify(secretVault).storeSystemKey(eq(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY), any(byte[].class));
    }

    @Test
    void resolveToken_extractorTokenIsReturnedWithoutCachingWhenNoVaultIsBound() {
        resolver.secretVault = null;
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("extracted-oauth-token"));
        assertEquals("extracted-oauth-token", resolver.resolveToken());
    }

    @Test
    void resolveToken_missingExtractorIsSkipped() {
        resolver.tokenExtractor = null;
        System.setProperty(CLAUDE_CODE_PROPERTY, "property-claude-key");
        assertEquals("property-claude-key", resolver.resolveToken());
    }

    @Test
    void resolveToken_emptyExtractorResultFallsThroughToTheProperty() {
        vaultEmpty();
        when(tokenExtractor.extractToken()).thenReturn(Optional.empty());
        System.setProperty(CLAUDE_CODE_PROPERTY, "property-claude-key");
        assertEquals("property-claude-key", resolver.resolveToken());
    }

    @Test
    void resolveToken_blankPropertyIsSkippedAndTheChainIsExhausted() {
        System.setProperty(CLAUDE_CODE_PROPERTY, "   ");
        Assumptions.assumeTrue(
                System.getenv(ConfigKeys.CLAUDE_CODE_API_KEY) == null,
                "a Claude Code API key is present in the environment");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> resolver.resolveToken());
        assertTrue(thrown.getMessage().contains("Claude Code provider requires active credentials"));
    }

    @Test
    void resolveToken_throwsIllegalStateExceptionWhenNoCredentialsAvailable() {
        when(secretVault.retrieveSystemKey(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.empty());
        when(tokenExtractor.extractToken()).thenReturn(Optional.empty());
        Assumptions.assumeTrue(
                System.getenv(ConfigKeys.CLAUDE_CODE_API_KEY) == null,
                "a Claude Code API key is present in the environment");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> resolver.resolveToken());
        assertTrue(thrown.getMessage().contains("AMSHA_CLAUDE_CODE_API_KEY"));
    }

    // ------------------------------------------------ resolveTokenWithGrant(): guards

    @Test
    void grantRequiresAnActorUserId() {
        assertThrows(IllegalStateException.class, () -> resolver.resolveTokenWithGrant(null, SESSION, GRANT_TOKEN));
        assertThrows(IllegalStateException.class, () -> resolver.resolveTokenWithGrant("  ", SESSION, GRANT_TOKEN));
    }

    @Test
    void grantRequiresASessionId() {
        assertThrows(IllegalStateException.class, () -> resolver.resolveTokenWithGrant(ACTOR, null, GRANT_TOKEN));
        assertThrows(IllegalStateException.class, () -> resolver.resolveTokenWithGrant(ACTOR, "  ", GRANT_TOKEN));
    }

    @Test
    void grantRequiresAnApprovalToken() {
        assertThrows(IllegalStateException.class, () -> resolver.resolveTokenWithGrant(ACTOR, SESSION, null));
        assertThrows(IllegalStateException.class, () -> resolver.resolveTokenWithGrant(ACTOR, SESSION, "  "));
    }

    @Test
    void grantRequiresAnIssuer() {
        resolver.approvalReceiptIssuer = null;
        resolver.approvalReceiptIssuerInstance = null;
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> resolver.resolveTokenWithGrant(ACTOR, SESSION, GRANT_TOKEN));
        assertTrue(thrown.getMessage().contains("ApprovalReceiptIssuer is unavailable"));
    }

    @Test
    void grantIsVerifiedThroughTheCdiIssuerWhenTheDirectFieldIsUnset() {
        ApprovalReceiptIssuer fromCdi = Mockito.mock(ApprovalReceiptIssuer.class);
        resolver.approvalReceiptIssuer = null;
        resolver.approvalReceiptIssuerInstance = resolvableInstance(fromCdi);
        when(fromCdi.verifyAndConsume(eq(GRANT_TOKEN), eq(OutboundPurpose.PROVIDER_OAUTH_EXCHANGE), eq(TARGET_HOST)))
                .thenReturn(receipt(ACTOR, SESSION));
        vaultHolds("grant-vault-token");

        assertEquals("grant-vault-token", resolver.resolveTokenWithGrant(ACTOR, SESSION, GRANT_TOKEN));
    }

    // --------------------------------------- resolveTokenWithGrant(): receipt validation

    @Test
    void grantRejectsATokenTheIssuerDoesNotRecognise() {
        issuerReturning(null);
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> resolver.resolveTokenWithGrant(ACTOR, SESSION, GRANT_TOKEN));
        assertTrue(thrown.getMessage().contains("invalid, expired, or already consumed"));
    }

    @Test
    void grantRejectsAnActorMismatch() {
        issuerReturning(receipt("someone-else", SESSION));
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> resolver.resolveTokenWithGrant(ACTOR, SESSION, GRANT_TOKEN));
        assertTrue(thrown.getMessage().contains("actor mismatch"));
    }

    @Test
    void grantRejectsASessionMismatch() {
        issuerReturning(receipt(ACTOR, "some-other-session"));
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> resolver.resolveTokenWithGrant(ACTOR, SESSION, GRANT_TOKEN));
        assertTrue(thrown.getMessage().contains("session mismatch"));
    }

    // ------------------------------------- resolveTokenWithGrant(): credential resolution

    @Test
    void grantReturnsAVaultCachedToken() {
        issuerReturning(receipt(ACTOR, SESSION));
        vaultHolds("  grant-vault-token  ");

        assertEquals("grant-vault-token", resolver.resolveTokenWithGrant(ACTOR, SESSION, GRANT_TOKEN));
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void grantFallsThroughWhenTheVaultTokenIsBlank() {
        issuerReturning(receipt(ACTOR, SESSION));
        vaultHolds("   ");
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("grant-extracted-token"));

        assertEquals("grant-extracted-token", resolver.resolveTokenWithGrant(ACTOR, SESSION, GRANT_TOKEN));
    }

    @Test
    void grantFallsThroughToTheExtractorAndCaches() {
        issuerReturning(receipt(ACTOR, SESSION));
        vaultEmpty();
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("grant-extracted-token"));
        AtomicReference<byte[]> stored = captureVaultWrite();

        assertEquals("grant-extracted-token", resolver.resolveTokenWithGrant(ACTOR, SESSION, GRANT_TOKEN));
        assertNotNull(stored.get());
        assertEquals("grant-extracted-token", new String(stored.get(), StandardCharsets.UTF_8));
    }

    @Test
    void grantFallsThroughToTheExtractorWithoutAVault() {
        issuerReturning(receipt(ACTOR, SESSION));
        resolver.secretVault = null;
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("grant-extracted-token"));

        assertEquals("grant-extracted-token", resolver.resolveTokenWithGrant(ACTOR, SESSION, GRANT_TOKEN));
    }

    @Test
    void grantThrowsWhenNoCredentialsRemain() {
        issuerReturning(receipt(ACTOR, SESSION));
        resolver.tokenExtractor = null;

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> resolver.resolveTokenWithGrant(ACTOR, SESSION, GRANT_TOKEN));
        assertTrue(thrown.getMessage().contains("not found after grant verification"));
    }

    @Test
    void grantThrowsWhenTheExtractorHasNoToken() {
        issuerReturning(receipt(ACTOR, SESSION));
        vaultEmpty();
        when(tokenExtractor.extractToken()).thenReturn(Optional.empty());

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> resolver.resolveTokenWithGrant(ACTOR, SESSION, GRANT_TOKEN));
        assertTrue(thrown.getMessage().contains("not found after grant verification"));
    }
}
