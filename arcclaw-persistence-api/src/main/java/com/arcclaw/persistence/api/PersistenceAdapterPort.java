package com.arcclaw.persistence.api;

public interface PersistenceAdapterPort {

    /**
     * Executes the given action within a transaction (typically REQUIRES_NEW
     * if called from a background thread) and handles any backend-specific
     * retries (e.g., SQLite BUSY).
     * 
     * @param sessionId the context session ID
     * @param operationName descriptive name for logging/metrics
     * @param action the action to execute, providing access to a PersistenceContext
     */
    void execute(String sessionId, String operationName, PersistenceAction action);
}
