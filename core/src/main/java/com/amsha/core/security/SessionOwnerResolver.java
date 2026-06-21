package com.amsha.core.security;

import java.util.Optional;

/**
 * SPI for resolving human-friendly owner identities (userIds) from raw credentials (e.g. API keys).
 * <p>
 * Implemented in the app module to bridge REST-level security to core channels.
 */
public interface SessionOwnerResolver {

    /**
     * Resolves an owner identity from a raw credential string.
     *
     * @param credential the raw key (e.g. from an X-AMSHA-API-KEY header)
     * @return the resolved userId / key label, or empty if invalid
     */
    Optional<String> resolveOwner(String credential);

    /**
     * Simple boolean check for whether the credential is valid (and not rate-limited).
     */
    boolean isValid(String credential);
}
