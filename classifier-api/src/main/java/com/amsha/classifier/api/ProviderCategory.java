package com.amsha.classifier.api;

/**
 * Explicit provider deployment category.
 * Enforces the invariant that in-process implementations may satisfy network-free
 * classifier SPIs, while self-hosted loopback and remote providers must not.
 */
public enum ProviderCategory {

    /**
     * In-process local implementation (such as ONNX). Synchronous, network-free,
     * payload-free, and eligible to satisfy classifier SPIs.
     */
    IN_PROCESS(true),

    /**
     * Separately hosted local or loopback service. Must not be placed behind
     * network-free classifier SPIs.
     */
    SELF_HOSTED(false),

    /**
     * External remote network-backed decision service. Must not be placed behind
     * network-free classifier SPIs or used to authorize its own network call.
     */
    REMOTE(false);

    private final boolean permitsClassifierSpi;

    ProviderCategory(boolean permitsClassifierSpi) {
        this.permitsClassifierSpi = permitsClassifierSpi;
    }

    /**
     * Indicates whether implementations in this category are eligible to satisfy
     * synchronous, network-free classifier SPIs.
     *
     * @return true if permitted for classifier SPIs
     */
    public boolean permitsClassifierSpi() {
        return permitsClassifierSpi;
    }
}
