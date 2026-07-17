package com.amsha.core.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Bridges durable LLM-profile mutations to the {@link ChatModelPool} so that a changed, rotated,
 * or deactivated connection/profile/route does not serve a stale pooled client. Adapters call
 * these methods after committing a write; invalidation is keyed by connection/profile id and is a
 * no-op when no pooled entry references that id (the legacy persona/group/global pool keys are not
 * indexed by connection/profile, so they are never disturbed).
 */
@ApplicationScoped
public class ModelCacheInvalidator {

    private final ChatModelPool pool;

    @Inject
    public ModelCacheInvalidator(ChatModelPool pool) {
        this.pool = pool;
    }

    protected ModelCacheInvalidator() {
        this(null);
    }

    /** Call after a connection is saved, updated, activated, or rotated. */
    public void onConnectionChanged(String connectionId) {
        if (connectionId == null) return;
        pool.invalidateByConnection(connectionId);
        pool.invalidateProfileRoutes();
    }

    /** Call after a model profile is saved, updated, activated, or deleted. */
    public void onProfileChanged(String profileId) {
        if (profileId == null) return;
        pool.invalidateByProfile(profileId);
        pool.invalidateProfileRoutes();
    }
}
