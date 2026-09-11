package com.amsha.llm.providers.oauth;

/**
 * Result of a governed provider OAuth token exchange or refresh.
 * Never encapsulates or exposes the raw token secret.
 */
public record ProviderOAuthResult(boolean success, String error, Integer expiresInSeconds) {

    public ProviderOAuthResult {
        if (success) {
            if (error != null && !error.isBlank()) {
                throw new IllegalArgumentException("Success result must not contain an error");
            }
            if (expiresInSeconds != null && expiresInSeconds <= 0) {
                throw new IllegalArgumentException("expiresInSeconds must be positive when present");
            }
        } else {
            if (error == null || error.isBlank()) {
                throw new IllegalArgumentException("Failure result must contain a non-blank error");
            }
            if (expiresInSeconds != null) {
                throw new IllegalArgumentException("Failure result must not have expiresInSeconds");
            }
        }
    }

    public static ProviderOAuthResult success(Integer expiresInSeconds) {
        return new ProviderOAuthResult(
                true, null, (expiresInSeconds != null && expiresInSeconds > 0) ? expiresInSeconds : null);
    }

    public static ProviderOAuthResult failure(String error) {
        return new ProviderOAuthResult(false, error != null ? error : "Operation failed", null);
    }
}
