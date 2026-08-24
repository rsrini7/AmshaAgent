package com.amsha.security.api;

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
     * Stores a secret in the stable vault scope of {@code userId}, independent of the active chat session.
     */
    void store(String keyName, byte[] secret, String userId);

    /**
     * Retrieves a decrypted secret.
     *
     * @param keyName The name of the secret.
     * @return An Optional containing the secret bytes (wrapped in VaultKey).
     */
    Optional<VaultKey> get(String keyName);

    /**
     * Retrieves a secret from the stable vault scope of {@code userId}.
     */
    Optional<VaultKey> get(String keyName, String userId);

    /**
     * Removes a secret from the stable vault scope of {@code userId}.
     */
    void remove(String keyName, String userId);

    /** Stores a secret in a workspace vault partition after authorizing the acting member. */
    void storeWorkspace(String keyName, byte[] secret, String workspaceId, String actorUserId);

    /** Retrieves a workspace secret after authorizing the acting member. */
    Optional<VaultKey> getWorkspace(String keyName, String workspaceId, String actorUserId);

    /** Removes a workspace secret after authorizing the acting member. */
    void removeWorkspace(String keyName, String workspaceId, String actorUserId);

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
