package com.amsha.classifier.api;

/**
 * Boundary contract for self-hosted local/loopback decision providers.
 * Must NOT implement or be placed behind network-free classifier SPIs.
 */
public interface SelfHostedDecisionProvider {

    /**
     * Identifies this provider as self-hosted.
     *
     * @return {@link ProviderCategory#SELF_HOSTED}
     */
    default ProviderCategory category() {
        return ProviderCategory.SELF_HOSTED;
    }
}
