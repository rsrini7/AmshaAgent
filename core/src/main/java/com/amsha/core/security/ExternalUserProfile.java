package com.amsha.core.security;

/**
 * Represents user profile metadata returned by an external identity provider.
 */
public record ExternalUserProfile(String providerUserId, String email, String displayName, String avatarUrl) {

    public ExternalUserProfile {
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("providerUserId is required");
        }
    }
}
