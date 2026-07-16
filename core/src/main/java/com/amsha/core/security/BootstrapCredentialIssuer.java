package com.amsha.core.security;

/**
 * SPI for issuing the first credential raw key for the bootstrap owner.
 */
public interface BootstrapCredentialIssuer {
    /**
     * Generates a new credential for the owner and returns it.
     * The digest of the key is persisted inside the credential store.
     */
    IssuedCredential issueCredential(String ownerId);

    void rollbackCredential(String ownerId, String keyDigest, String rawKey);
}
