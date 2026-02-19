package com.arcclaw.core.security;

import com.arcclaw.core.scope.WorkspaceScopeHolder;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Optional;

@Dependent
public class SecretVaultImpl implements SecretVault {

    @Inject
    Argon2idKeyDerivation keyDerivation;

    @Inject
    AesGcmSivCipher cipher;

    @Override
    public void init(String sessionId) {
        // Validation only - key derivation happens on demand to avoid holding keys in
        // memory needlessly
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session ID required for vault init");
        }
    }

    @Override
    @Transactional
    public void store(String keyName, byte[] secret) {
        String sessionId = WorkspaceScopeHolder.get();
        if (sessionId == null) {
            throw new IllegalStateException("Active session required to store secrets");
        }

        // 1. Derive Key
        try (VaultKey sessionKey = keyDerivation.deriveSessionKey(sessionId)) {
            // 2. Encrypt (AAD = keyName)
            AesGcmSivCipher.CipherResult result = cipher.encrypt(secret, sessionKey, keyName);

            // 3. Persist
            EncryptedSecret entity = EncryptedSecret.findByKey(sessionId, keyName);
            if (entity == null) {
                entity = new EncryptedSecret();
                entity.id = new EncryptedSecret.EncryptedSecretId(sessionId, keyName);
            }
            entity.ciphertext = result.ciphertext();
            entity.nonce = result.nonce();
            entity.version = 1;

            entity.persist();
        } // sessionKey auto-closed (wiped) here
    }

    @Override
    public Optional<VaultKey> get(String keyName) {
        String sessionId = WorkspaceScopeHolder.get();
        if (sessionId == null) {
            throw new IllegalStateException("Active session required to retrieve secrets");
        }

        // 1. Fetch
        EncryptedSecret entity = EncryptedSecret.findByKey(sessionId, keyName);
        if (entity == null) {
            return Optional.empty();
        }

        // 2. Derive Key
        try (VaultKey sessionKey = keyDerivation.deriveSessionKey(sessionId)) {
            // 3. Decrypt
            byte[] plaintext = cipher.decrypt(entity.ciphertext, entity.nonce, sessionKey, keyName);
            return Optional.of(new VaultKey(plaintext));
        } catch (SecurityException e) {
            // Log security event?
            throw e;
        }
        // sessionKey auto-closed
    }

    @Override
    @Transactional
    public void rotateSecrets(String sessionId, String oldPepper, String newPepper) {
        // Not implemented in Phase 2 iteration 1
        // Requires separating Pepper injection logic to allow "old" and "new" peppers
        // simultaneously.
        throw new UnsupportedOperationException("Key rotation not yet implemented in Phase 2");
    }
}
