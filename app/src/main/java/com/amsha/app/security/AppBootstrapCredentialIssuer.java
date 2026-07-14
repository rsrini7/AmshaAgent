package com.amsha.app.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import com.amsha.core.security.BootstrapCredentialIssuer;
import com.amsha.core.security.CryptoConstants;
import com.amsha.core.security.IssuedCredential;
import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AppBootstrapCredentialIssuer implements BootstrapCredentialIssuer {

    private final SecureRandom secureRandom = new SecureRandom();

    @Inject
    ApiCredentialStorePort apiCredentialStore;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @ConfigProperty(name = "amsha.auth.hmac-key")
    String hmacSecret;

    @Override
    public IssuedCredential issueCredential(String ownerId) {
        // Generate high entropy key
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String rawKey = "sk-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        // Compute HMAC digest
        String digest = computeDigest(rawKey);

        // Save to durable store
        Instant now = Instant.now();
        ApiCredentialDto cred =
                new ApiCredentialDto("bootstrap-cred", ownerId, digest, "OWNER", 1, null, null, now, now);
        apiCredentialStore.save(cred);

        // Register in transient cache immediately
        apiKeyRegistry.register(ownerId, rawKey);

        return new IssuedCredential(rawKey, digest);
    }

    @Override
    public void rollbackCredential(String ownerId, String keyDigest, String rawKey) {
        apiCredentialStore.deleteById("bootstrap-cred");
        apiKeyRegistry.unregister(rawKey);
    }

    private String computeDigest(String rawKey) {
        try {
            Mac mac = Mac.getInstance(CryptoConstants.HMAC_SHA256);
            mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), CryptoConstants.HMAC_SHA256));
            byte[] bytes = mac.doFinal(rawKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC digest", e);
        }
    }
}
