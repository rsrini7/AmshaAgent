package com.arcclaw.core.security;

import java.util.Optional;

public interface SecretVault {

    /**
     * Initializes the vault for the given session.
     * Often implicit, but explicit init allows forcing key derivation early.
     */
    void init(String sessionId);

    /**
     * Stores a secret.
     * 
     * @param keyName The unique name of the secret (e.g., "OPENAI_API_KEY").
     * @param secret  The secret bytes.
     */
    void store(String keyName, byte[] secret);

    /**
     * Retrieves a decrypted secret.
     * 
     * @param keyName The name of the secret.
     * @return An Optional containing the secret bytes (wrapped in VaultKey).
     */
    Optional<VaultKey> get(String keyName);

    /**
     * Rotates all secrets for the given session to a NEW derived key.
     * This is an expensive operation: decrypt all -> re-encrypt with new pepper ->
     * save.
     */
    void rotateSecrets(String sessionId, String oldPepper, String newPepper);
}
