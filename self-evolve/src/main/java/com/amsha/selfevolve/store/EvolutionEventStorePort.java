package com.amsha.selfevolve.store;

import java.util.List;

import com.amsha.selfevolve.api.EvolutionEvent;

/**
 * Persistence port for evolution events.
 * Implementations: SQLite, PostgreSQL, Filesystem.
 * NoOp implementation returned when self-evolve is disabled.
 */
public interface EvolutionEventStorePort {

    /**
     * Persists a batch of events. Must be idempotent (INSERT OR IGNORE).
     * Events are flushed from the conversation buffer as a batch.
     *
     * @param events non-empty list of events to persist
     * @return number of events actually persisted (may be less if duplicates)
     */
    int insertBatch(List<EvolutionEvent> events);

    /**
     * Retrieves events for a session, ordered by creation time.
     *
     * @param sessionId session to query
     * @param limit     max events to return (0 = no limit)
     * @return ordered list of events
     */
    List<EvolutionEvent> getBySession(String sessionId, int limit);

    /**
     * Retrieves events for a user across all sessions, ordered by creation time.
     *
     * @param userId user to query
     * @param limit  max events to return
     * @return ordered list of events
     */
    List<EvolutionEvent> getByUser(String userId, int limit);

    /**
     * Purges events older than the specified retention days.
     *
     * @param retentionDays delete events older than this
     * @return number of events purged
     */
    int purgeExpired(int retentionDays);
}
