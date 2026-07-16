package com.amsha.app.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import com.amsha.core.security.BootstrapCredentialIssuer;
import com.amsha.core.security.IssuedCredential;
import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;

@ApplicationScoped
public class AppBootstrapCredentialIssuer implements BootstrapCredentialIssuer {

    @Inject
    ApiCredentialStorePort apiCredentialStore;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @Override
    public IssuedCredential issueCredential(String ownerId) {
        // Generate high entropy key
        byte[] bytes = new byte[32];
        String rawKey;
        try {
            new SecureRandom().nextBytes(bytes);
            rawKey = "sk-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }

        // Compute HMAC digest
        String digest = apiKeyRegistry.computeDigest(rawKey);

        // Save to durable store
        Instant now = Instant.now();
        ApiCredentialDto cred =
                new ApiCredentialDto("bootstrap-cred", ownerId, digest, "OWNER", 1, null, null, now, now);
        if (!apiCredentialStore.save(cred)) {
            throw new IllegalStateException("Bootstrap credential changed concurrently");
        }

        // Register in transient cache immediately
        apiKeyRegistry.register(ownerId, rawKey);

        return new IssuedCredential(rawKey, digest);
    }

    @Override
    public void rollbackCredential(String ownerId, String keyDigest, String rawKey) {
        apiCredentialStore.deleteById("bootstrap-cred");
        apiKeyRegistry.unregister(rawKey);
    }
}
