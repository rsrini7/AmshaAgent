package com.arcclaw.core.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.security.CryptoConstants;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultConstants;
import com.arcclaw.core.security.VaultKey;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AuditHashUtil {

    private static final Logger log = Logger.getLogger(AuditHashUtil.class);

    private volatile byte[] hmacKey;
    private final Instance<SecretVault> vaultInstance;

    @Inject
    public AuditHashUtil(Instance<SecretVault> vaultInstance) {
        this.vaultInstance = vaultInstance;
    }

    /**
     * Eagerly primer the HMAC key during startup.
     * This moves the expensive Argon2id derivation out of the first
     * per-session auditing transaction to avoid transaction timeouts.
     */
    void onStart(@Observes @Priority(500) StartupEvent ev) {
        try {
            log.debug("Priming Audit HMAC key...");
            getHmacKey();
            log.info("AuditHashUtil: HMAC key primed successfully.");
        } catch (Exception e) {
            log.warnf("AuditHashUtil: Failed to primer HMAC key at startup: %s. " +
                      "It will be lazily initialized on first use.", e.getMessage());
        }
    }

    private byte[] getHmacKey() {
        byte[] result = hmacKey;
        if (result == null) {
            synchronized (this) {
                result = hmacKey;
                if (result == null) {
                    SecretVault vault = vaultInstance.get();
                    result = vault.retrieveSystemKey(VaultConstants.AUDIT_HMAC_KEY_NAME)
                            .map(VaultKey::get)
                            .map(byte[]::clone)
                            .orElseThrow(() -> {
                                return new IllegalStateException("Audit HMAC key '" + VaultConstants.AUDIT_HMAC_KEY_NAME
                                        + "' is missing from SecretVault. " +
                                        "Ensure DevSecretSeeder has seeded it in dev/test, " +
                                        "or StartupSecurityValidator has verified it in prod.");
                            });
                    hmacKey = result;
                }
            }
        }
        return result;
    }

    /**
     * Computes the SHA-256 hash of the input string and returns it as a hex string.
     * Prevents raw PII or secrets from being stored directly when hashing goals or
     * inputs.
     * 
     * @param input The raw input to hash (e.g., user goal, reflex input)
     * @return 64-character hex string representing the SHA-256 hash,
     *         "genesis-fallback-null-input" if input is null.
     * @throws IllegalStateException if SHA-256 is unavailable (JVM is broken).
     */
    public String sha256hex(String input) {
        if (input == null) {
            // Genesis parent link: 64 zeros
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // SHA-256 must be available for audit chaining — failure means the JVM is broken
            throw new IllegalStateException("SHA-256 unavailable — JVM is broken", e);
        }
    }


    public String hmacHex(String data) {
        try {
            byte[] key = getHmacKey();
            Mac mac = Mac.getInstance(CryptoConstants.HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(key, CryptoConstants.HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            if (hash == null) return "error-null-mac-result";
            return HexFormat.of().formatHex(hash);
        } catch (IllegalStateException | SecurityException e) {
            throw new IllegalStateException(
                "Audit HMAC key unavailable. Chain integrity cannot be guaranteed. " +
                "Ensure DevSecretSeeder has seeded '" + VaultConstants.AUDIT_HMAC_KEY_NAME + "' before first audit write.", e);
        } catch (Exception e) {
            // Check if it's a shutdown race
            if (Arc.container() == null) {
                return "error-shutdown";
            }
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }
}
