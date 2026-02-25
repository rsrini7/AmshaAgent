package com.arcclaw.core.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public class AuditHashUtil {

    private AuditHashUtil() {}

    /**
     * Computes the SHA-256 hash of the input string and returns it as a hex string.
     * Prevents raw PII or secrets from being stored directly when hashing goals or inputs.
     * 
     * @param input The raw input to hash (e.g., user goal, reflex input)
     * @return 64-character hex string representing the SHA-256 hash, "null" if input is null,
     *         or "hash-error" if SHA-256 is unavailable.
     */
    public static String sha256hex(String input) {
        if (input == null) return "null";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "hash-error";
        }
    }
}
