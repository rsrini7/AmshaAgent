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

import com.amsha.llm.api.oauth.AgyOAuthTokenExtractor;
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
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Unit tests for {@link AgyTokenResolver}.
 *
 * <h3>Why the CLI probe is stubbed statically</h3>
 *
 * {@code resolveToken} calls the <em>static</em> {@link AgyCliExecutor#isAvailable()} and returns
 * {@code "agy-cli-session"} when it is true. That method reads {@code ANTIGRAVITY_AGENTAPI_EXE},
 * {@code ~/.local/bin/agy} and {@code PATH}, so its answer is a property of the machine — and on a
 * machine where the CLI <em>is</em> installed the resolver short-circuits before the vault, the
 * extractor and the environment fallbacks are ever consulted. An earlier revision of this class
 * guarded those assertions with {@code if (AgyCliExecutor.isAvailable()) { ... } else { ... }},
 * which meant half the class silently never ran. The probe is now pinned with
 * {@link Mockito#mockStatic} so every branch is deterministic and reachable on any machine.
 *
 * <p>The environment-variable tail of the fallback chain ({@code AMSHA_AGY_API_KEY},
 * {@code GEMINI_API_KEY}, {@code GOOGLE_API_KEY}) cannot be controlled from a test, so the one
 * assertion that depends on all three being absent is guarded with an assumption rather than
 * silently skipped — see {@link #missingCredentialsThrow()}.
 */
class AgyTokenResolverTest {

    /** System property consulted first in the environment fallback chain. */
    private static final String API_KEY_PROPERTY = "amsha.llm.agy.api-key";

    /** Host the grant path must bind the approval receipt to. */
    private static final String TARGET_HOST = "generativelanguage.googleapis.com";

    private static final String ACTOR = "user-1";
    private static final String SESSION = "session-1";
    private static final String GRANT_TOKEN = "grant-token";

    private AgyOAuthTokenExtractor tokenExtractor;
    private SecretVault secretVault;
    private AgyTokenResolver resolver;
    private MockedStatic<AgyCliExecutor> cli;
    private String savedProperty;

    @BeforeEach
    void setUp() {
        tokenExtractor = Mockito.mock(AgyOAuthTokenExtractor.class);
        secretVault = Mockito.mock(SecretVault.class);
        resolver = new AgyTokenResolver();
        resolver.tokenExtractor = tokenExtractor;
        resolver.secretVault = secretVault;

        cli = Mockito.mockStatic(AgyCliExecutor.class);
        cli.when(AgyCliExecutor::isAvailable).thenReturn(false);

        savedProperty = System.getProperty(API_KEY_PROPERTY);
        System.clearProperty(API_KEY_PROPERTY);
    }

    @AfterEach
    void tearDown() {
        cli.close();
        if (savedProperty == null) {
            System.clearProperty(API_KEY_PROPERTY);
        } else {
            System.setProperty(API_KEY_PROPERTY, savedProperty);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void cliAvailable() {
        cli.when(AgyCliExecutor::isAvailable).thenReturn(true);
    }

    private void vaultHolds(String rawToken) {
        when(secretVault.retrieveSystemKey(VaultConstants.AGY_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.of(new VaultKey(rawToken.getBytes(StandardCharsets.UTF_8))));
    }

    private void vaultEmpty() {
        when(secretVault.retrieveSystemKey(VaultConstants.AGY_OAUTH_TOKEN_KEY)).thenReturn(Optional.empty());
    }

    /** Captures what the resolver writes to the vault so the cached bytes can be asserted. */
    private AtomicReference<byte[]> captureVaultWrite() {
        AtomicReference<byte[]> stored = new AtomicReference<>();
        Mockito.doAnswer(inv -> {
                    stored.set(((byte[]) inv.getArgument(1)).clone());
                    return null;
                })
                .when(secretVault)
                .storeSystemKey(eq(VaultConstants.AGY_OAUTH_TOKEN_KEY), any(byte[].class));
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
                ApprovalReceiptIssuer.oauthExchangeSessionId("agy", boundSessionId),
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
        cliAvailable();
        assertEquals("explicit-agy-key", resolver.resolveToken(Optional.of("explicit-agy-key")));
        verify(secretVault, never()).retrieveSystemKey(any());
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void blankExplicitKeyFallsThroughToTheCli() {
        cliAvailable();
        assertEquals("agy-cli-session", resolver.resolveToken(Optional.of("   ")));
    }

    @Test
    void nullExplicitKeyFallsThroughToTheCli() {
        cliAvailable();
        assertEquals("agy-cli-session", resolver.resolveToken(null));
    }

    @Test
    void noArgOverloadDelegatesWithAnEmptyOptional() {
        cliAvailable();
        assertEquals("agy-cli-session", resolver.resolveToken());
    }

    @Test
    void cliSessionWinsOverTheVaultAndTheExtractor() {
        cliAvailable();
        vaultHolds("vault-token-that-must-not-be-read");
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("extractor-token-that-must-not-be-read"));
        assertEquals("agy-cli-session", resolver.resolveToken(Optional.empty()));
        verify(secretVault, never()).retrieveSystemKey(any());
        verify(tokenExtractor, never()).extractToken();
    }

    // ------------------------------------------------------ resolveToken(): vault path

    @Test
    void vaultTokenIsTrimmedAndUsedWhenTheCliIsAbsent() {
        vaultHolds("  vault-agy-token  ");
        assertEquals("vault-agy-token", resolver.resolveToken(Optional.empty()));
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void blankVaultTokenFallsThroughToTheExtractor() {
        vaultHolds("   ");
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("extracted-agy-token"));
        assertEquals("extracted-agy-token", resolver.resolveToken(Optional.empty()));
    }

    // -------------------------------------------------- resolveToken(): extractor path

    @Test
    void extractorTokenIsCachedInTheVault() {
        vaultEmpty();
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("extracted-agy-token"));
        AtomicReference<byte[]> stored = captureVaultWrite();

        assertEquals("extracted-agy-token", resolver.resolveToken(Optional.empty()));
        assertNotNull(stored.get(), "the extracted token must be written back to the vault");
        assertEquals("extracted-agy-token", new String(stored.get(), StandardCharsets.UTF_8));
    }

    @Test
    void extractorTokenIsReturnedWithoutCachingWhenNoVaultIsBound() {
        resolver.secretVault = null;
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("extracted-agy-token"));
        assertEquals("extracted-agy-token", resolver.resolveToken(Optional.empty()));
    }

    @Test
    void missingExtractorIsSkipped() {
        resolver.tokenExtractor = null;
        System.setProperty(API_KEY_PROPERTY, "property-agy-key");
        assertEquals("property-agy-key", resolver.resolveToken(Optional.empty()));
    }

    // ------------------------------------------- resolveToken(): environment fallbacks

    @Test
    void systemPropertyIsUsedAsAFallback() {
        System.setProperty(API_KEY_PROPERTY, "property-agy-key");
        assertEquals("property-agy-key", resolver.resolveToken(Optional.empty()));
    }

    @Test
    void emptyExtractorResultFallsThroughToTheEnvironment() {
        vaultEmpty();
        when(tokenExtractor.extractToken()).thenReturn(Optional.empty());
        System.setProperty(API_KEY_PROPERTY, "property-agy-key");
        assertEquals("property-agy-key", resolver.resolveToken(Optional.empty()));
    }

    @Test
    void blankSystemPropertyIsSkipped() {
        System.setProperty(API_KEY_PROPERTY, "   ");
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> resolver.resolveToken(Optional.empty()));
        assertTrue(thrown.getMessage().contains("Antigravity provider requires active credentials"));
    }

    @Test
    void missingCredentialsThrow() {
        // The remaining links in the chain are AMSHA_AGY_API_KEY / GEMINI_API_KEY / GOOGLE_API_KEY, which a
        // test cannot unset. Skip (visible in the report) rather than assert something the machine decides.
        Assumptions.assumeTrue(
                System.getenv("AMSHA_AGY_API_KEY") == null
                        && System.getenv("GEMINI_API_KEY") == null
                        && System.getenv("GOOGLE_API_KEY") == null,
                "an agy/Gemini API key is present in the environment");

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> resolver.resolveToken(Optional.empty()));
        assertTrue(thrown.getMessage().contains("Antigravity provider requires active credentials"));
        assertTrue(thrown.getMessage().contains("AMSHA_AGY_API_KEY"));
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
