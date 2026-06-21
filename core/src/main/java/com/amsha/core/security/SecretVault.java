package com.amsha.core.security;

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

    void store(String keyName, byte[] secret, String userId);

    /**
     * Retrieves a decrypted secret.
     *
     * @param keyName The name of the secret.
     * @return An Optional containing the secret bytes (wrapped in VaultKey).
     */
    Optional<VaultKey> get(String keyName);

    Optional<VaultKey> get(String keyName, String userId);

    // rotateSecrets removed as per key-rotation policy deferral
    /**
     * Retrieves a system-level key (not tied to any specific workspace session).
     */
    Optional<VaultKey> retrieveSystemKey(String keyName);

    /**
     * Checks if a system-level key exists.
     */
    boolean systemKeyExists(String keyName);

    /**
     * Stores a system-level key.
     */
    void storeSystemKey(String keyName, byte[] secret);

    /**
     * Removes a system-level key.
     */
    void removeSystemKey(String keyName);
}
