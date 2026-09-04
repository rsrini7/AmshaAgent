package com.amsha.persistence.api;

import java.time.Instant;
import java.util.List;

/**
 * Port for persisting and querying execution trace events and run summaries.
 * §1.2: Core-persistence boundary.
 */
public interface ExecutionTraceStorePort {

    /**
     * Persists a single trace record.
     * Idempotent: inserting an existing eventId is a no-op.
     */
    void save(ExecutionTraceDto dto);

    /**
     * Persists a batch of trace records.
     * Idempotent: duplicates are ignored.
     */
    void saveBatch(List<ExecutionTraceDto> dtos);

    /**
     * Retrieves all trace events for a specific run, ordered strictly by sequence ASC.
     */
    List<ExecutionTraceDto> findByRunId(String runId);

    /**
     * Retrieves a bounded window of trace events for scrubbing and pagination.
     *
     * @param runId        mandatory logical run identifier
     * @param fromSequence minimum sequence number (inclusive)
     * @param toSequence   maximum sequence number (inclusive, nullable)
     * @param offset       0-based offset within the filtered sequence
     * @param limit        maximum number of events (capped at server max)
     */
    List<ExecutionTraceDto> findByRunIdBounded(String runId, long fromSequence, Long toSequence, int offset, int limit);

    /**
     * Counts the total number of events recorded for a given run.
     */
    long countByRunId(String runId);

    /**
     * Lists distinct run summaries for a session, ordered by latest activity DESC.
     */
    List<ExecutionRunSummaryDto> findRunsBySessionId(String sessionId, int offset, int limit);

    /**
     * Prunes trace records created before the cutoff instant.
     *
     * @return number of records deleted
     */
    long pruneBefore(Instant cutoff);

    /**
     * Prunes runs exceeding the configured run count limit per session,
     * removing the oldest runs (by latest activity) when a session exceeds maxRunsPerSession.
     *
     * @param maxRunsPerSession maximum allowed runs per session (must be > 0)
     * @return number of records deleted
     */
    long pruneExcessRuns(int maxRunsPerSession);

    /**
     * Deletes all traces belonging to a specific session (e.g. on session deletion).
     */
    void deleteBySessionId(String sessionId);
}
