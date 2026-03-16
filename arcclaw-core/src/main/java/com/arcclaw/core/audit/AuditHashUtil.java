package com.arcclaw.core.audit;

import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultKey;
import com.arcclaw.core.security.CryptoConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@ApplicationScoped
public class AuditHashUtil {

    private static final Logger LOG = Logger.getLogger(AuditHashUtil.class);
    private static final String AUDIT_HMAC_KEY_NAME = "arcclaw-audit-hmac-key";

    private final byte[] hmacKey;

    @Inject
    public AuditHashUtil(SecretVault vault) {
        // Retrieved at construction time from vault.
        // SecretVault must be initialized before AuditHashUtil — enforced by CDI.
        this.hmacKey = vault.retrieveSystemKey(AUDIT_HMAC_KEY_NAME)
            .map(VaultKey::get)
            .orElseThrow(() -> new IllegalStateException(
                "Audit HMAC key '" + AUDIT_HMAC_KEY_NAME + "' is missing from SecretVault. " +
                "Ensure DevSecretSeeder has seeded it in dev/test, " +
                "or StartupSecurityValidator has verified it in prod."));
    }

    /**
     * Computes the SHA-256 hash of the input string and returns it as a hex string.
     * Prevents raw PII or secrets from being stored directly when hashing goals or inputs.
     * 
     * @param input The raw input to hash (e.g., user goal, reflex input)
     * @return 64-character hex string representing the SHA-256 hash, "null" if input is null,
     *         or "hash-error" if SHA-256 is unavailable.
     */
    public String sha256hex(String input) {
        if (input == null) return "null";
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
            Mac mac = Mac.getInstance(CryptoConstants.HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(hmacKey, CryptoConstants.HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }
}
