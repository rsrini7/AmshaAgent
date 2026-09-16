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

import com.amsha.llm.api.oauth.KimchiOAuthTokenExtractor;
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
 * Unit tests for {@link KimchiTokenResolver}, mirroring the {@link AgyTokenResolverTest} spec.
 *
 * <p>Unlike the Antigravity resolver there is no CLI probe to pin — the fallback chain ends in
 * {@code System.getProperty} / {@code System.getenv}, so the property links are driven directly and the
 * environment links are guarded with an assumption (a test cannot unset them). The grant path reuses the
 * same receipt fixture as its siblings.
 */
class KimchiTokenResolverTest {

    /** First link of the property fallback chain; also the name of an environment variable further down. */
    private static final String KIMCHI_KEY_PROPERTY = "AMSHA_KIMCHI_API_KEY";

    /** Second link of the property fallback chain. */
    private static final String KIMCHI_ALT_PROPERTY = "amsha.llm.kimchi.api-key";

    /** Host the grant path must bind the approval receipt to. */
    private static final String TARGET_HOST = "llm.kimchi.dev";

    private static final String ACTOR = "user-1";
    private static final String SESSION = "session-1";
    private static final String GRANT_TOKEN = "grant-token";

    private KimchiOAuthTokenExtractor tokenExtractor;
    private SecretVault secretVault;
    private KimchiTokenResolver resolver;
    private String savedPrimary;
    private String savedAlternate;

    @BeforeEach
    void setUp() {
        tokenExtractor = Mockito.mock(KimchiOAuthTokenExtractor.class);
        secretVault = Mockito.mock(SecretVault.class);
        resolver = new KimchiTokenResolver();
        resolver.tokenExtractor = tokenExtractor;
        resolver.secretVault = secretVault;

        savedPrimary = System.getProperty(KIMCHI_KEY_PROPERTY);
        savedAlternate = System.getProperty(KIMCHI_ALT_PROPERTY);
        System.clearProperty(KIMCHI_KEY_PROPERTY);
        System.clearProperty(KIMCHI_ALT_PROPERTY);
    }

    @AfterEach
    void tearDown() {
        restoreProperty(KIMCHI_KEY_PROPERTY, savedPrimary);
        restoreProperty(KIMCHI_ALT_PROPERTY, savedAlternate);
    }

    private static void restoreProperty(String name, String saved) {
        if (saved == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, saved);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void vaultHolds(String rawToken) {
        when(secretVault.retrieveSystemKey(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.of(new VaultKey(rawToken.getBytes(StandardCharsets.UTF_8))));
    }

    private void vaultEmpty() {
        when(secretVault.retrieveSystemKey(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY))
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
                .storeSystemKey(eq(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY), any(byte[].class));
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
                ApprovalReceiptIssuer.oauthExchangeSessionId("kimchi", boundSessionId),
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
    void resolveIssuerPrefersTheDirectlyInjectedField() {
        ApprovalReceiptIssuer direct = Mockito.mock(ApprovalReceiptIssuer.class);
        resolver.approvalReceiptIssuer = direct;
        resolver.approvalReceiptIssuerInstance = resolvableInstance(Mockito.mock(ApprovalReceiptIssuer.class));
        assertSame(direct, resolver.resolveIssuer(), "the direct field must win over the CDI instance");
    }

    @Test
    void resolveIssuerFallsBackToTheCdiInstance() {
        ApprovalReceiptIssuer fromCdi = Mockito.mock(ApprovalReceiptIssuer.class);
        resolver.approvalReceiptIssuer = null;
        resolver.approvalReceiptIssuerInstance = resolvableInstance(fromCdi);
        assertSame(fromCdi, resolver.resolveIssuer());
    }

    @Test
    void resolveIssuerReturnsNullWhenNothingIsAvailable() {
        resolver.approvalReceiptIssuer = null;
        resolver.approvalReceiptIssuerInstance = null;
        assertNull(resolver.resolveIssuer());
    }

    @Test
    void resolveIssuerReturnsNullWhenTheCdiInstanceIsNotResolvable() {
        resolver.approvalReceiptIssuer = null;
        resolver.approvalReceiptIssuerInstance = unresolvableInstance();
        assertNull(resolver.resolveIssuer());
    }

    // ------------------------------------------------ resolveToken(): precedence chain

    @Test
    void explicitKeyTakesPrecedence() {
        String token = resolver.resolveToken(Optional.of("explicit-kimchi-key"));
        assertEquals("explicit-kimchi-key", token);
        verify(secretVault, never()).retrieveSystemKey(any());
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void blankExplicitKeyFallsThroughToTheVault() {
        vaultHolds("vault-kimchi-token");
        assertEquals("vault-kimchi-token", resolver.resolveToken(Optional.of("   ")));
    }

    @Test
    void nullExplicitKeyFallsThroughToTheVault() {
        vaultHolds("vault-kimchi-token");
        assertEquals("vault-kimchi-token", resolver.resolveToken(null));
    }

    @Test
    void noArgOverloadDelegatesWithAnEmptyOptional() {
        vaultHolds("vault-kimchi-token");
        assertEquals("vault-kimchi-token", resolver.resolveToken());
    }

    // ------------------------------------------------------ resolveToken(): vault path

    @Test
    void vaultCachedTokenIsUsedWhenPresent() {
        byte[] tokenBytes = "vault-kimchi-token".getBytes(StandardCharsets.UTF_8);
        when(secretVault.retrieveSystemKey(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.of(new VaultKey(tokenBytes)));

        String token = resolver.resolveToken(Optional.empty());
        assertEquals("vault-kimchi-token", token);
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void vaultTokenIsTrimmedBeforeUse() {
        vaultHolds("  vault-kimchi-token  ");
        assertEquals("vault-kimchi-token", resolver.resolveToken(Optional.empty()));
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void blankVaultTokenFallsThroughToTheExtractor() {
        vaultHolds("   ");
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("extracted-kimchi-token"));
        assertEquals("extracted-kimchi-token", resolver.resolveToken(Optional.empty()));
    }

    // -------------------------------------------------- resolveToken(): extractor path

    @Test
    void extractorIsUsedAndCachedWhenVaultMisses() {
        when(secretVault.retrieveSystemKey(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.empty());
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("extracted-kimchi-token"));

        AtomicReference<byte[]> stored = new AtomicReference<>();
        Mockito.doAnswer(inv -> {
                    byte[] bytes = inv.getArgument(1);
                    stored.set(bytes.clone());
                    return null;
                })
                .when(secretVault)
                .storeSystemKey(eq(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY), any(byte[].class));

        String token = resolver.resolveToken(Optional.empty());
        assertEquals("extracted-kimchi-token", token);
        assertNotNull(stored.get());
        assertEquals("extracted-kimchi-token", new String(stored.get(), StandardCharsets.UTF_8));
    }

    @Test
    void extractorTokenIsReturnedWithoutCachingWhenNoVaultIsBound() {
        resolver.secretVault = null;
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("extracted-kimchi-token"));
        assertEquals("extracted-kimchi-token", resolver.resolveToken(Optional.empty()));
    }

    @Test
    void missingExtractorIsSkipped() {
        resolver.tokenExtractor = null;
        System.setProperty(KIMCHI_KEY_PROPERTY, "property-kimchi-key");
        assertEquals("property-kimchi-key", resolver.resolveToken(Optional.empty()));
    }

    @Test
    void emptyExtractorResultFallsThroughToTheEnvironment() {
        vaultEmpty();
        when(tokenExtractor.extractToken()).thenReturn(Optional.empty());
        System.setProperty(KIMCHI_KEY_PROPERTY, "property-kimchi-key");
        assertEquals("property-kimchi-key", resolver.resolveToken(Optional.empty()));
    }

    // ------------------------------------------- resolveToken(): property fallbacks

    @Test
    void primarySystemPropertyIsUsedAsAFallback() {
        System.setProperty(KIMCHI_KEY_PROPERTY, "property-kimchi-key");
        assertEquals("property-kimchi-key", resolver.resolveToken(Optional.empty()));
    }

    @Test
    void alternateSystemPropertyIsUsedWhenThePrimaryIsBlank() {
        System.setProperty(KIMCHI_KEY_PROPERTY, "   ");
        System.setProperty(KIMCHI_ALT_PROPERTY, "alt-kimchi-key");
        assertEquals("alt-kimchi-key", resolver.resolveToken(Optional.empty()));
    }

    @Test
    void blankSystemPropertyIsSkipped() {
        System.setProperty(KIMCHI_KEY_PROPERTY, "   ");
        Assumptions.assumeTrue(
                System.getenv("AMSHA_KIMCHI_API_KEY") == null && System.getenv("KIMCHI_API_KEY") == null,
                "a Kimchi API key is present in the environment");

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> resolver.resolveToken(Optional.empty()));
        assertTrue(thrown.getMessage().contains("Kimchi provider requires active credentials"));
    }

    @Test
    void missingCredentialsThrow() {
        // The remaining links are the AMSHA_KIMCHI_API_KEY / KIMCHI_API_KEY environment variables, which a
        // test cannot unset. Skip (visible in the report) rather than assert something the machine decides.
        Assumptions.assumeTrue(
                System.getenv("AMSHA_KIMCHI_API_KEY") == null && System.getenv("KIMCHI_API_KEY") == null,
                "a Kimchi API key is present in the environment");

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> resolver.resolveToken(Optional.empty()));
        assertTrue(thrown.getMessage().contains("Kimchi provider requires active credentials"));
        assertTrue(thrown.getMessage().contains("AMSHA_KIMCHI_API_KEY"));
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
