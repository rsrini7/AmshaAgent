package com.amsha.core.security;

/**
 * Result of inspecting a text payload for credentials or authentication secrets.
 *
 * <p>Contains strictly categorical metadata and boolean flags. Never contains the matched secret string.
 */
public record CredentialDetectionResult(boolean detected, String credentialType) {

    public static final CredentialDetectionResult CLEAN = new CredentialDetectionResult(false, "NONE");

    public CredentialDetectionResult {
        if (!detected) {
            credentialType = "NONE";
        } else if (credentialType == null || credentialType.isBlank()) {
            credentialType = "UNKNOWN_CREDENTIAL";
        }
    }

    public static CredentialDetectionResult of(String credentialType) {
        return new CredentialDetectionResult(true, credentialType != null ? credentialType : "UNKNOWN_CREDENTIAL");
    }
}
