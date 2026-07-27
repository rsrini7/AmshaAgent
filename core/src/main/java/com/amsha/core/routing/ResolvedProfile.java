package com.amsha.core.routing;

/**
 * A single resolved, eligible model profile in a route chain.
 *
 * <p>Carries only the non-secret routing identity plus a lazy, closable credential view. The
 * credential bytes are resolved on demand by the runtime client factory and must never be logged,
 * cached, or retained beyond the client build.</p>
 */
public record ResolvedProfile(
        String profileId,
        String connectionId,
        String modelId,
        String providerAdapterId,
        String scopeType,
        String scopeId,
        int connectionRevision,
        int profileRevision,
        boolean enabled) {

    public boolean isEligible() {
        return enabled;
    }
}
