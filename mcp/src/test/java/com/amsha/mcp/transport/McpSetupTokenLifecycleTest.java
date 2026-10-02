package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.mcp.config.McpToolAllowlist;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Covers the half of {@link McpSetupTokenService} that {@link McpSetupTokenServiceTest} leaves dark: the three
 * HMAC-key bootstrapping paths, the vault persistence round-trip, the serialization format (including the legacy
 * five-field form), and every fail-closed branch of {@code redeem}.
 *
 * <p><strong>Findings recorded here (pinned, not fixed).</strong>
 * <ul>
 *   <li><em>Buffers handed to the vault are wiped straight after the call.</em> Both {@code initialize}
 *       ({@code storeSystemKey(HMAC_VAULT_KEY, generated)}) and {@code persistGrant}
 *       ({@code store(vaultKey, payload, userId)}) pass a mutable array to the vault and then zero <em>that same
 *       array</em> in a {@code finally} block. It is safe today only because {@code SecretVaultImpl} encrypts
 *       synchronously inside the call ({@code SecretVaultImpl.java:180} and {@code :410}) before the wipe — a
 *       vault that retained the reference (a deferred write, or a plaintext cache) would persist an all-zero, and
 *       therefore predictable, HMAC key. {@code initialize} already copies for its own field
 *       ({@code Arrays.copyOf}) but not for the vault, which reads as an oversight rather than a decision.
 *       {@link #theVaultIsHandedABufferThatIsWipedImmediatelyAfterTheCall} and
 *       {@link #initializeMintsAndPersistsA256BitSystemKeyWhenTheVaultHasNone} pin the current behaviour so that
 *       changing it is deliberate.
 *   <li><em>A structurally invalid persisted grant escapes as an exception.</em> {@code deserialize} throws and
 *       nothing in {@code redeem} catches it, so a corrupted vault entry propagates {@code IllegalArgumentException}
 *       out of a method whose every other refusal is {@link Optional#empty()}.
 * </ul>
 *
 * <p>Two further guards are deliberately **not** chased, because no single-threaded input can reach them: the
 * expiry test inside {@code redeem}'s condition is only reachable for a grant reloaded from the vault (an
 * in-memory expired grant is removed by {@code sweepExpiredGrants()} at the top of the same call, so it lands on
 * the {@code grant == null} path instead — the vault-reload path is what this class exercises), and
 * {@code !grants.remove(tokenDigest, grant)} needs the entry to change between the {@code get} and the
 * {@code remove}.
 */
class McpSetupTokenLifecycleTest {

    private static final String ALLOWLIST_SECRET = "test-secret";
    private static final String HMAC_KEY_NAME = "MCP_SETUP_TOKEN_HMAC_KEY";

    /**
     * A service whose HMAC secret comes from the allowlist, so that two independent instances derive the same
     * digests — which is what makes the vault round-trip testable.
     */
    private static McpSetupTokenService serviceWithAllowlistSecret() {
        McpSetupTokenService service = new McpSetupTokenService();
        McpToolAllowlist allowlist = mock(McpToolAllowlist.class);
        when(allowlist.setupTokenSecret()).thenReturn(Optional.of(ALLOWLIST_SECRET));
        service.allowlist = allowlist;
        service.initialize();
        return service;
    }

    private static McpSetupTokenService serviceWithEmptyAllowlist(SecretVault vault) {
        McpSetupTokenService service = new McpSetupTokenService();
        McpToolAllowlist allowlist = mock(McpToolAllowlist.class);
        when(allowlist.setupTokenSecret()).thenReturn(Optional.empty());
        service.allowlist = allowlist;
        service.secretVault = vault;
        return service;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** The current six-field wire form: user.channel.chatId.server.mode.epochMillis. */
    private static String payload(
            String user,
            String channelType,
            String channelId,
            String server,
            McpSetupTokenService.SetupMode mode,
            Instant expiry) {
        return String.join(
                ".",
                encode(user),
                encode(channelType),
                encode(channelId),
                encode(server),
                encode(mode.name()),
                Long.toString(expiry.toEpochMilli()));
    }

    /** The pre-{@code SetupMode} five-field form: user.channel.chatId.server.epochMillis. */
    private static String legacyPayload(
            String user, String channelType, String channelId, String server, Instant expiry) {
        return String.join(
                ".",
                encode(user),
                encode(channelType),
                encode(channelId),
                encode(server),
                Long.toString(expiry.toEpochMilli()));
    }

    private static McpSetupTokenService serviceReading(String storedPayload) {
        SecretVault vault = mock(SecretVault.class);
        when(vault.get(anyString(), eq("user-1")))
                .thenReturn(Optional.of(new VaultKey(storedPayload.getBytes(StandardCharsets.UTF_8))));
        McpSetupTokenService service = serviceWithAllowlistSecret();
        service.secretVault = vault;
        return service;
    }

    // ---- HMAC key bootstrapping ---------------------------------------------

    @Test
    void initializePrefersTheAllowlistSecretAndNeverTouchesTheVault() {
        SecretVault vault = mock(SecretVault.class);
        McpSetupTokenService service = new McpSetupTokenService();
        McpToolAllowlist allowlist = mock(McpToolAllowlist.class);
        when(allowlist.setupTokenSecret()).thenReturn(Optional.of(ALLOWLIST_SECRET));
        service.allowlist = allowlist;
        service.secretVault = vault;

        service.initialize();

        verifyNoInteractions(vault);
        assertTrue(service.redeem(service.issue("user-1", "chat", "chat-1", "server-1"), "user-1")
                .isPresent());
    }

    @Test
    void initializeReusesAnExistingSystemKeyInsteadOfMintingANewOne() {
        SecretVault vault = mock(SecretVault.class);
        when(vault.retrieveSystemKey(HMAC_KEY_NAME))
                .thenReturn(Optional.of(new VaultKey("stable-key".getBytes(StandardCharsets.UTF_8))));
        McpSetupTokenService service = serviceWithEmptyAllowlist(vault);

        service.initialize();

        verify(vault).retrieveSystemKey(HMAC_KEY_NAME);
        verify(vault, never()).storeSystemKey(anyString(), any());
    }

    @Test
    void initializeMintsAndPersistsA256BitSystemKeyWhenTheVaultHasNone() {
        SecretVault vault = mock(SecretVault.class);
        when(vault.retrieveSystemKey(HMAC_KEY_NAME)).thenReturn(Optional.empty());
        McpSetupTokenService service = serviceWithEmptyAllowlist(vault);

        service.initialize();

        ArgumentCaptor<byte[]> minted = ArgumentCaptor.forClass(byte[].class);
        verify(vault).storeSystemKey(eq(HMAC_KEY_NAME), minted.capture());
        assertEquals(32, minted.getValue().length, "a fresh HMAC key must be 256 bits");
        assertArrayEquals(
                new byte[32],
                minted.getValue(),
                "FINDING: the vault is handed the very array initialize's finally block then zeroes, so the "
                        + "captured value is all zeros. Safe only because SecretVaultImpl encrypts synchronously "
                        + "inside the call (SecretVaultImpl.java:410); a vault that retained the reference would "
                        + "persist an all-zero HMAC key.");
    }

    @Test
    void initializeStillWorksWhenThereIsNoVaultAtAll() {
        McpSetupTokenService service = serviceWithEmptyAllowlist(null);

        service.initialize();

        String token = service.issue("user-1", "chat", "chat-1", "server-1");
        assertEquals("server-1", service.redeem(token, "user-1").orElseThrow().serverName());
    }

    /**
     * The {@code allowlist == null} arm of {@code initialize}. Both collaborators are {@code @Inject} fields, so in
     * a running container neither is null; the guard exists for hand-constructed instances, and what it must do is
     * documented — mint a process-local secret (which invalidates outstanding links on restart) rather than fail.
     */
    @Test
    void initializeMintsAProcessLocalSecretWhenNeitherCollaboratorIsConfigured() {
        McpSetupTokenService service = new McpSetupTokenService();

        service.initialize();

        String token = service.issue("user-1", "chat", "chat-1", "server-1");
        assertTrue(service.redeem(token, "user-1").isPresent());
    }

    // ---- issue ---------------------------------------------------------------

    @Test
    void theThreeArgumentIssueDefaultsTheChannelIdToUnknown() {
        McpSetupTokenService service = serviceWithAllowlistSecret();

        String token = service.issue("user-1", "telegram", "server-1");

        assertEquals("unknown", service.redeem(token, "user-1").orElseThrow().channelId());
    }

    @Test
    void issueRejectsEveryMissingFieldByName() {
        McpSetupTokenService service = serviceWithAllowlistSecret();

        assertAll(
                () -> assertEquals(
                        "userId is required",
                        assertThrows(IllegalArgumentException.class, () -> service.issue(null, "chat", "chat-1", "s"))
                                .getMessage()),
                () -> assertEquals(
                        "userId is required",
                        assertThrows(IllegalArgumentException.class, () -> service.issue("  ", "chat", "chat-1", "s"))
                                .getMessage()),
                () -> assertEquals(
                        "channelType is required",
                        assertThrows(IllegalArgumentException.class, () -> service.issue("u", null, "chat-1", "s"))
                                .getMessage()),
                () -> assertEquals(
                        "channelType is required",
                        assertThrows(IllegalArgumentException.class, () -> service.issue("u", "", "chat-1", "s"))
                                .getMessage()),
                () -> assertEquals(
                        "channelId is required",
                        assertThrows(IllegalArgumentException.class, () -> service.issue("u", "chat", null, "s"))
                                .getMessage()),
                () -> assertEquals(
                        "channelId is required",
                        assertThrows(IllegalArgumentException.class, () -> service.issue("u", "chat", " ", "s"))
                                .getMessage()),
                () -> assertEquals(
                        "serverName is required",
                        assertThrows(IllegalArgumentException.class, () -> service.issue("u", "chat", "chat-1", null))
                                .getMessage()),
                () -> assertEquals(
                        "serverName is required",
                        assertThrows(IllegalArgumentException.class, () -> service.issue("u", "chat", "chat-1", ""))
                                .getMessage()));
    }

    // ---- redeem: fail-closed inputs -----------------------------------------

    @Test
    void redeemRefusesNullAndBlankInputsWithoutBurningAValidToken() {
        McpSetupTokenService service = serviceWithAllowlistSecret();
        String token = service.issue("user-1", "chat", "chat-1", "server-1");

        assertTrue(service.redeem(null, "user-1").isEmpty());
        assertTrue(service.redeem("", "user-1").isEmpty());
        assertTrue(service.redeem("   ", "user-1").isEmpty());
        assertTrue(service.redeem(token, null).isEmpty());
        assertTrue(service.redeem(token, "").isEmpty());
        assertTrue(service.redeem(token, "   ").isEmpty());

        assertTrue(service.redeem(token, "user-1").isPresent(), "none of the refusals may have consumed it");
    }

    @Test
    void redeemRefusesATokenIssuedForAnotherUserAndKeepsItForItsOwner() {
        McpSetupTokenService service = serviceWithAllowlistSecret();
        String token = service.issue("user-1", "chat", "chat-1", "server-1");

        assertTrue(service.redeem(token, "user-2").isEmpty(), "a token must not be redeemable by another user");
        assertTrue(service.redeem(token, "user-1").isPresent(), "and the refusal must not have consumed it");
    }

    @Test
    void redeemRefusesAChannelTypeMismatchButAcceptsTheMatchingOne() {
        McpSetupTokenService service = serviceWithAllowlistSecret();
        String token = service.issue("user-1", "telegram", "chat-1", "server-1");

        assertTrue(service.redeem(token, "user-1", "chat", "chat-1").isEmpty());
        assertTrue(service.redeem(token, "user-1", "telegram", "chat-1").isPresent());
    }

    // ---- vault persistence round-trip ---------------------------------------

    @Test
    void theVaultIsHandedABufferThatIsWipedImmediatelyAfterTheCall() {
        SecretVault vault = mock(SecretVault.class);
        McpSetupTokenService service = serviceWithAllowlistSecret();
        service.secretVault = vault;

        service.issue("user-1", "chat", "chat-1", "server-1");

        ArgumentCaptor<byte[]> handedOver = ArgumentCaptor.forClass(byte[].class);
        verify(vault).store(anyString(), handedOver.capture(), eq("user-1"));
        assertArrayEquals(
                new byte[handedOver.getValue().length],
                handedOver.getValue(),
                "FINDING: persistGrant wipes the serialized grant right after handing it to the vault; safe only "
                        + "because SecretVaultImpl encrypts synchronously (SecretVaultImpl.java:180)");
    }

    @Test
    void aTokenIsRecoverableFromTheVaultOnceTheInMemoryGrantIsGone() {
        SecretVault vault = mock(SecretVault.class);
        AtomicReference<String> storedKey = new AtomicReference<>();
        AtomicReference<byte[]> storedPayload = new AtomicReference<>();
        // Copy the payload *during* the call: persistGrant zeroes its buffer in a finally block immediately
        // afterwards, so keeping the captured reference would only ever observe the wiped array.
        doAnswer(invocation -> {
                    storedKey.set(invocation.getArgument(0));
                    storedPayload.set(((byte[]) invocation.getArgument(1)).clone());
                    return null;
                })
                .when(vault)
                .store(anyString(), any(byte[].class), eq("user-1"));

        McpSetupTokenService issuer = serviceWithAllowlistSecret();
        issuer.secretVault = vault;
        String token = issuer.issue("user-1", "chat", "chat-1", "server-1");

        assertTrue(
                storedKey.get().startsWith("MCP_SETUP_TOKEN:"),
                () -> "the vault key must be namespaced, was: " + storedKey.get());
        assertFalse(
                Arrays.equals(new byte[storedPayload.get().length], storedPayload.get()),
                "the copy taken during the call must hold the serialized grant, not the wiped buffer");

        // A second instance shares the HMAC secret but starts with an empty in-memory map, so the grant can only
        // come back through the vault.
        SecretVault secondVault = mock(SecretVault.class);
        when(secondVault.get(storedKey.get(), "user-1")).thenReturn(Optional.of(new VaultKey(storedPayload.get())));
        McpSetupTokenService redeemer = serviceWithAllowlistSecret();
        redeemer.secretVault = secondVault;

        McpSetupTokenService.SetupGrant grant = redeemer.redeem(token, "user-1").orElseThrow();

        assertEquals("user-1", grant.userId());
        assertEquals("chat-1", grant.channelId());
        assertEquals("server-1", grant.serverName());
        assertEquals(McpSetupTokenService.SetupMode.ONBOARD, grant.mode());
        verify(secondVault).remove(storedKey.get(), "user-1");
    }

    @Test
    void aPersistedGrantThatHasExpiredIsRefused() {
        String stale = payload(
                "user-1",
                "chat",
                "chat-1",
                "server-1",
                McpSetupTokenService.SetupMode.ONBOARD,
                Instant.now().minusSeconds(60));

        assertTrue(
                serviceReading(stale).redeem("some-token", "user-1").isEmpty(),
                "a grant whose expiry has passed must not be honoured, even when reloaded from the vault");
    }

    @Test
    void aLegacyFiveFieldGrantDecodesAsOnboardMode() {
        String legacy = legacyPayload(
                "user-1", "chat", "chat-1", "server-1", Instant.now().plusSeconds(300));

        McpSetupTokenService.SetupGrant grant =
                serviceReading(legacy).redeem("legacy-token", "user-1").orElseThrow();

        assertEquals(
                McpSetupTokenService.SetupMode.ONBOARD,
                grant.mode(),
                "a payload written before SetupMode existed must still onboard");
        assertEquals("server-1", grant.serverName());
    }

    @Test
    void aMalformedPersistedGrantThrowsRatherThanBeingTreatedAsAbsent() {
        String malformed = encode("user-1") + "." + encode("chat") + "." + encode("chat-1");

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> serviceReading(malformed).redeem("some-token", "user-1"));

        assertEquals("Invalid MCP setup grant", thrown.getMessage());
    }

    // ---- digest failure ------------------------------------------------------

    @Test
    void anEmptyConfiguredSecretFailsWithAClearErrorRatherThanARawCryptoException() {
        McpSetupTokenService service = new McpSetupTokenService();
        McpToolAllowlist allowlist = mock(McpToolAllowlist.class);
        when(allowlist.setupTokenSecret()).thenReturn(Optional.of(""));
        service.allowlist = allowlist;
        service.initialize();

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> service.issue("user-1", "chat", "chat-1", "server-1"));

        assertEquals("MCP setup token digest unavailable", thrown.getMessage());
        assertNotNull(thrown.getCause(), "the underlying crypto failure must be preserved as the cause");
    }
}
