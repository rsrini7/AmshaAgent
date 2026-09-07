package com.amsha.core.security;

/**
 * Fail-closed contract for evaluating text payloads for credentials, API tokens,
 * passwords, private keys, and provider secrets prior to LLM or external invocation.
 */
public interface CredentialDetector {

    /**
     * Inspects the input text for any raw credentials or secrets.
     *
     * @param text input to inspect (may be null)
     * @return true if credentials were detected; false otherwise
     */
    default boolean containsCredentials(String text) {
        return detect(text).detected();
    }

    /**
     * Performs detection and returns categorical result without echoing matched secret.
     *
     * @param text input to inspect (may be null)
     * @return detection result
     */
    CredentialDetectionResult detect(String text);
}
