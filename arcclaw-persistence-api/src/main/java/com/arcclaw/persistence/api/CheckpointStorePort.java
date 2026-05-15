package com.arcclaw.persistence.api;

/**
 * Port interface for checkpoint persistence operations.
 * This interface isolates persistence concerns from the core domain.
 * 
 * All operations are handled through this adapter interface. Implementations
 * (e.g., SqliteCheckpointStoreAdapter) encapsulate retry logic, transaction
 * boundaries, and backend-specific behavior.
 * 
 * Uses CheckpointDto (a simple DTO) instead of JPA entities to avoid circular
 * dependencies. Adapters translate between CheckpointDto and their internal
 * domain models.
 * 
 * <h2>Interface grouping (ISP)</h2>
 * Methods are organized into logical groups below. Adapters needing only
 * a subset of capabilities can reference the corresponding sub-interface.
 * 
 * @see CheckpointCrudPort
 * @see CheckpointTransitionPort
 * @see CheckpointSessionPort
 * @see CheckpointLifecyclePort
 */
public interface CheckpointStorePort extends
    CheckpointCrudPort,
    CheckpointTransitionPort,
    CheckpointSessionPort,
    CheckpointLifecyclePort {

}
