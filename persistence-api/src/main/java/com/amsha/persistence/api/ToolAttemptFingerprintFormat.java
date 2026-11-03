package com.amsha.persistence.api;

/**
 * Canonical fingerprint prefixes and validators shared by persistence contracts and the core
 * invocation identity model.
 */
public final class ToolAttemptFingerprintFormat {

    public static final int ACTION_FINGERPRINT_VERSION = 1;
    public static final String ACTION_V1_PREFIX = "sha256:v1:";
    public static final String TARGET_FINGERPRINT_VERSION = "v1";
    public static final String TARGET_V1_PREFIX = "target:sha256:" + TARGET_FINGERPRINT_VERSION + ":";

    private ToolAttemptFingerprintFormat() {}

    /**
     * Returns whether {@code fingerprint} has a valid versioned SHA-256 action fingerprint format.
     */
    public static boolean isValidActionFingerprint(String fingerprint) {
        if (fingerprint == null || !fingerprint.startsWith("sha256:v")) {
            return false;
        }
        int versionSeparator = fingerprint.indexOf(':', "sha256:v".length());
        if (versionSeparator < 0) {
            return false;
        }
        String version = fingerprint.substring("sha256:v".length(), versionSeparator);
        return isPositiveCanonicalInteger(version) && isLowercaseHex64(fingerprint.substring(versionSeparator + 1));
    }

    /**
     * Returns whether {@code fingerprint} has a valid action fingerprint for the supplied version.
     */
    public static boolean isValidActionFingerprint(String fingerprint, int version) {
        if (version <= 0 || fingerprint == null) {
            return false;
        }
        String prefix = "sha256:v" + version + ":";
        return fingerprint.startsWith(prefix) && isLowercaseHex64(fingerprint.substring(prefix.length()));
    }

    /**
     * Returns whether {@code fingerprint} has the required V1 target fingerprint format.
     */
    public static boolean isValidTargetFingerprint(String fingerprint) {
        return fingerprint != null
                && fingerprint.startsWith(TARGET_V1_PREFIX)
                && isLowercaseHex64(fingerprint.substring(TARGET_V1_PREFIX.length()));
    }

    private static boolean isPositiveCanonicalInteger(String value) {
        if (value.isEmpty() || value.charAt(0) < '1' || value.charAt(0) > '9') {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            char digit = value.charAt(i);
            if (digit < '0' || digit > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isLowercaseHex64(String hex) {
        if (hex.length() != 64) {
            return false;
        }
        for (int i = 0; i < hex.length(); i++) {
            char digit = hex.charAt(i);
            if (!((digit >= '0' && digit <= '9') || (digit >= 'a' && digit <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}
