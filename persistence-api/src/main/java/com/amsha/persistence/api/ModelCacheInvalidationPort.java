package com.amsha.persistence.api;

/**
 * Port published by core so that store implementations can signal durable LLM-profile
 * mutations without importing core routing internals (AGENTS.md §5: persistence impls
 * depend on persistence-api only). Core binds the concrete invalidator that flushes
 * pooled chat-model clients.
 */
public interface ModelCacheInvalidationPort {

    /** Call after a connection is saved, updated, activated, or rotated. */
    void onConnectionChanged(String connectionId);

    /** Call after a model profile is saved, updated, activated, or deleted. */
    void onProfileChanged(String profileId);
}
