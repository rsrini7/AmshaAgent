package com.arcclaw.core.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.jboss.logging.Logger;

import com.arcclaw.core.security.CryptoConstants;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultKey;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class AuditHashUtil {

    private static final Logger log = Logger.getLogger(AuditHashUtil.class);
    private static volatile boolean keyMissingLogged = false;
    private static final String AUDIT_HMAC_KEY_NAME = "arcclaw-audit-hmac-key";

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
    void onStart(@Observes @jakarta.annotation.Priority(500) StartupEvent ev) {
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
                    result = vault.retrieveSystemKey(AUDIT_HMAC_KEY_NAME)
                            .map(VaultKey::get)
                            .map(byte[]::clone)
                            .orElseThrow(() -> {
                                String msg = "Audit HMAC key '" + AUDIT_HMAC_KEY_NAME
                                        + "' is missing from SecretVault. " +
                                        "Ensure DevSecretSeeder has seeded it in dev/test, " +
                                        "or StartupSecurityValidator has verified it in prod.";
                                if (!keyMissingLogged) {
                                    log.error(msg);
                                    keyMissingLogged = true;
                                }
                                return new IllegalStateException(msg);
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
     * @return 64-character hex string representing the SHA-256 hash, "null" if
     *         input is null,
     *         or "hash-error" if SHA-256 is unavailable.
     */
    public String sha256hex(String input) {
        if (input == null)
            return "null";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "hash-error";
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
            // Specific handling for missing/invalid key to avoid huge stack traces in logs.
            // For SecurityException (e.g. decryption failed), we log it once at warn level
            // but return placeholder to prevent log flooding during background retries.
            if (e instanceof SecurityException) {
                log.warnf("Audit HMAC key decryption failed (Integrity check failed). " +
                          "Audit logs for this session will be degraded. Error: %s", e.getMessage());
            }
            return "error-missing-hmac-key";
        } catch (Exception e) {
            // Check if it's a shutdown race
            if (io.quarkus.arc.Arc.container() == null) {
                return "error-shutdown";
            }
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }
}
