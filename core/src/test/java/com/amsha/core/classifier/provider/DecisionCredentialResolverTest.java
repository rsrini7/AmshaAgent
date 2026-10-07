package com.amsha.core.classifier.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;

import org.junit.jupiter.api.Test;

/**
 * Pins the one place a provider's credential <em>reference</em> becomes material.
 *
 * <p>Two properties matter and are easy to regress: the resolved bytes must be detached from the vault's
 * buffer, because the resolver closes the vault key (zeroing it) before returning; and a refusal must
 * describe the provider without echoing the reference value, which is operator configuration.
 */
class DecisionCredentialResolverTest {

    private static final String PROVIDER = "typesafe";
    private static final String REF = "DECISION_TYPESAFE_API_KEY";
    private static final byte[] SECRET = "s3cret-token".getBytes(StandardCharsets.UTF_8);

    @Test
    void aBlankReferenceResolvesToEmptyWithoutConsultingTheVault() {
        TestVault vault = new TestVault();

        assertTrue(new DecisionCredentialResolver(vault).resolve(PROVIDER, "").isEmpty());
        assertTrue(
                new DecisionCredentialResolver(vault).resolve(PROVIDER, "   ").isEmpty());
        assertTrue(new DecisionCredentialResolver(vault).resolve(PROVIDER, null).isEmpty());

        assertEquals(0, vault.retrieveCalls, "a blank reference must not reach the vault");
    }

    @Test
    void aConfiguredReferenceResolvesToTheStoredBytes() {
        TestVault vault = new TestVault();
        vault.put(REF, SECRET.clone());

        Optional<byte[]> resolved = new DecisionCredentialResolver(vault).resolve(PROVIDER, REF);

        assertTrue(resolved.isPresent());
        assertArrayEquals(SECRET, resolved.get());
    }

    @Test
    void theResolvedBytesSurviveTheVaultKeyBeingClosed() {
        // The resolver closes the vault key inside resolve(), which wipes the vault's own buffer. The
        // returned array must be a detached copy, or the caller would be handed zeroes.
        TestVault vault = new TestVault();
        byte[] stored = SECRET.clone();
        vault.put(REF, stored);

        byte[] resolved =
                new DecisionCredentialResolver(vault).resolve(PROVIDER, REF).orElseThrow();

        assertArrayEquals(SECRET, resolved, "the returned credential must not have been wiped");
        assertTrue(isZeroed(stored), "the vault's own buffer should have been wiped by close()");
    }

    @Test
    void anUnresolvableReferenceIsRefused() {
        TestVault vault = new TestVault();

        var thrown = assertThrows(
                DecisionProviderUnavailableException.class,
                () -> new DecisionCredentialResolver(vault).resolve(PROVIDER, REF));

        assertTrue(thrown.getMessage().contains(PROVIDER), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("not present in the vault"), thrown.getMessage());
    }

    @Test
    void aRefusalNeverEchoesTheReferenceValue() {
        TestVault vault = new TestVault();

        var thrown = assertThrows(
                DecisionProviderUnavailableException.class,
                () -> new DecisionCredentialResolver(vault).resolve(PROVIDER, REF));

        assertFalse(thrown.getMessage().contains(REF), thrown.getMessage());
    }

    @Test
    void aVaultFailureIsReportedAsARefusalWithTheCauseAttached() {
        TestVault vault = new TestVault();
        vault.failWith(new IllegalStateException("vault locked"));

        var thrown = assertThrows(
                DecisionProviderUnavailableException.class,
                () -> new DecisionCredentialResolver(vault).resolve(PROVIDER, REF));

        assertTrue(thrown.getMessage().contains(PROVIDER), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("rejected"), thrown.getMessage());
        assertTrue(thrown.getCause() instanceof IllegalStateException, "the cause should be preserved");
    }

    @Test
    void zeroWipesCredentialMaterialAndToleratesNull() {
        byte[] credential = SECRET.clone();

        DecisionCredentialResolver.zero(credential);
        assertTrue(isZeroed(credential));

        // A caller that resolved nothing has nothing to wipe; zero must not throw on the absent case.
        DecisionCredentialResolver.zero(null);
    }

    @Test
    void theResolverRequiresAVault() {
        assertThrows(NullPointerException.class, () -> new DecisionCredentialResolver(null));
    }

    private static boolean isZeroed(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Minimal {@link SecretVault} double: only {@code retrieveSystemKey} is meaningful, because it is the
     * only member the resolver uses. The rest throw, so a future change that reaches for another member
     * fails loudly here rather than silently succeeding against a stub.
     */
    private static final class TestVault implements SecretVault {

        private final Map<String, byte[]> keys = new HashMap<>();
        private int retrieveCalls;
        private RuntimeException failure;

        void put(String name, byte[] secret) {
            keys.put(name, secret);
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public Optional<VaultKey> retrieveSystemKey(String keyName) {
            retrieveCalls++;
            if (failure != null) {
                throw failure;
            }
            byte[] secret = keys.get(keyName);
            return secret == null ? Optional.empty() : Optional.of(new VaultKey(secret));
        }

        @Override
        public void init(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void store(String keyName, byte[] secret) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void store(String keyName, byte[] secret, String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<VaultKey> get(String keyName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<VaultKey> get(String keyName, String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void remove(String keyName, String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void storeWorkspace(String keyName, byte[] secret, String workspaceId, String actorUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<VaultKey> getWorkspace(String keyName, String workspaceId, String actorUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeWorkspace(String keyName, String workspaceId, String actorUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean systemKeyExists(String keyName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void storeSystemKey(String keyName, byte[] secret) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeSystemKey(String keyName) {
            throw new UnsupportedOperationException();
        }
    }
}
