package com.amsha.classifier.api;

/**
 * Boundary contract for remote network-backed decision providers.
 * Must NOT implement or be placed behind network-free classifier SPIs or used
 * to authorize its own outbound network calls.
 */
public interface RemoteDecisionProvider {

    /**
     * Identifies this provider as remote.
     *
     * @return {@link ProviderCategory#REMOTE}
     */
    default ProviderCategory category() {
        return ProviderCategory.REMOTE;
    }
}
