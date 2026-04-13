package com.arcclaw.persistence.api;

/**
 * Port for executing database actions with backend-specific retry logic.
 * 
 * Rationale (§5.14.1): Retry-on-BUSY is a SQLite-specific concern.
 * This port allows Core (AsyncPersistenceEngine) to delegate writes
 * without knowing about SQLite locking states.
 */
public interface PersistenceAdapterPort {

    /**
     * Executes the given action within a transaction (typically REQUIRES_NEW
     * if called from a background thread) and handles any backend-specific
     * retries (e.g., SQLite BUSY).
     * 
     * @param sessionId the context session ID
     * @param operationName descriptive name for logging/metrics
     * @param action the action to execute
     */
    void execute(String sessionId, String operationName, Runnable action);
}
