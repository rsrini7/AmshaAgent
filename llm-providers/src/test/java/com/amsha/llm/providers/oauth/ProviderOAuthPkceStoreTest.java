package com.amsha.llm.providers.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderOAuthPkceStoreTest {

    private ProviderOAuthPkceStore store;

    @BeforeEach
    void setUp() {
        store = new ProviderOAuthPkceStore();
    }

    @Test
    void beginAndValidateCallback_roundTrip() {
        ProviderOAuthExchangeService.PkceParameters params = store.beginAuthorization("codex", "user-1", "sess-1");

        assertFalse(params.codeVerifier().isBlank());
        assertFalse(params.codeChallenge().isBlank());
        assertFalse(params.state().isBlank());
        assertTrue(store.validateCallbackState("codex", params.state(), "user-1", "sess-1"));
    }

    @Test
    void validateCallback_rejectsMismatchedBinding() {
        ProviderOAuthExchangeService.PkceParameters params = store.beginAuthorization("codex", "user-1", "sess-1");

        assertFalse(store.validateCallbackState("codex", params.state(), "other-user", "sess-1"));
        assertFalse(store.validateCallbackState("codex", params.state(), "user-1", "other-session"));
        assertFalse(store.validateCallbackState("kimchi", params.state(), "user-1", "sess-1"));
        assertFalse(store.validateCallbackState("codex", "unknown-state", "user-1", "sess-1"));
    }

    @Test
    void validateForExchange_refreshFlowRequiresNoPkce() {
        Optional<String> error = store.validateForExchange(
                "codex", Map.of("grant_type", "refresh_token"), "user-1", "sess-1", null, null);

        assertTrue(error.isEmpty());
    }

    @Test
    void validateForExchange_authorizationCodeRequiresVerifierAndState() {
        Optional<String> missingVerifier = store.validateForExchange(
                "codex", Map.of("grant_type", "authorization_code", "code", "c"), "user-1", "sess-1", null, "s");
        assertTrue(missingVerifier.isPresent());

        Optional<String> missingState = store.validateForExchange(
                "codex", Map.of("grant_type", "authorization_code", "code", "c"), "user-1", "sess-1", "v", null);
        assertTrue(missingState.isPresent());
    }

    @Test
    void validateForExchange_acceptsMatchingVerifierAndConsumePreventsReuse() {
        ProviderOAuthExchangeService.PkceParameters params = store.beginAuthorization("codex", "user-1", "sess-1");

        Optional<String> error = store.validateForExchange(
                "codex",
                Map.of("grant_type", "authorization_code", "code", "c"),
                "user-1",
                "sess-1",
                params.codeVerifier(),
                params.state());
        assertTrue(error.isEmpty());

        store.consume(params.state());
        assertFalse(store.validateCallbackState("codex", params.state(), "user-1", "sess-1"));
    }

    @Test
    void validateForExchange_rejectsTamperedVerifier() {
        ProviderOAuthExchangeService.PkceParameters params = store.beginAuthorization("codex", "user-1", "sess-1");

        Optional<String> error = store.validateForExchange(
                "codex",
                Map.of("grant_type", "authorization_code", "code", "c"),
                "user-1",
                "sess-1",
                "tampered-verifier",
                params.state());
        assertTrue(error.isPresent());
    }

    @Test
    void computeS256Challenge_matchesStoredChallenge() {
        ProviderOAuthExchangeService.PkceParameters params = store.beginAuthorization("codex", "user-1", "sess-1");

        assertEquals(params.codeChallenge(), ProviderOAuthPkceStore.computeS256Challenge(params.codeVerifier()));
    }

    @Test
    void claimForExchange_isSingleFlightAndReleaseAllowsRetry() {
        ProviderOAuthExchangeService.PkceParameters params = store.beginAuthorization("codex", "user-1", "sess-1");
        Map<String, String> form = Map.of("grant_type", "authorization_code", "code", "c");

        assertTrue(store.claimForExchange("codex", form, "user-1", "sess-1", params.codeVerifier(), params.state())
                .isEmpty());
        assertTrue(store.claimForExchange("codex", form, "user-1", "sess-1", params.codeVerifier(), params.state())
                .isPresent());

        store.release(params.state());
        assertTrue(store.claimForExchange("codex", form, "user-1", "sess-1", params.codeVerifier(), params.state())
                .isEmpty());
        store.consume(params.state());
    }

    @Test
    void beginAuthorization_rejectsWhenStoreCapacityIsFull() {
        Map<String, ProviderOAuthPkceStore.PendingAuthorization> full = new HashMap<>();
        Instant expiry = Instant.now().plus(Duration.ofHours(1));
        for (int i = 0; i < ProviderOAuthPkceStore.MAX_ENTRIES; i++) {
            full.put(
                    "state-" + i,
                    new ProviderOAuthPkceStore.PendingAuthorization(
                            "codex", "user-" + i, "session-" + i, "challenge", expiry));
        }
        ProviderOAuthPkceStore fullStore = new ProviderOAuthPkceStore(full);

        assertThrows(IllegalStateException.class, () -> fullStore.beginAuthorization("codex", "user", "session"));
    }
}
