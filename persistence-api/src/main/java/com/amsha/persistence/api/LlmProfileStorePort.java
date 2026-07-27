package com.amsha.persistence.api;

import java.util.List;
import java.util.Optional;

/**
 * Durable, non-secret persistence for LLM connections, their vault secret references, model
 * profiles, and route assignments. Records are inert until activated by the runtime resolver (O3).
 *
 * <p>Scope-authorized read/write, revision-based optimistic concurrency, and an expected-revision
 * activation covering every record finalized by one draft are the contract. Adapters implement
 * one port-level contract for SQLite, PostgreSQL, and filesystem.</p>
 */
public interface LlmProfileStorePort {

    void saveConnection(LlmConnectionDto connection);

    Optional<LlmConnectionDto> findConnection(String id);

    List<LlmConnectionDto> listConnectionsByScope(String scopeType, String scopeId);

    List<LlmConnectionDto> listConnectionsByCreatedBy(String createdByUserId);

    boolean updateConnection(LlmConnectionDto connection, int expectedRevision);

    /**
     * Atomically activates a connection and all of its dependent model profiles and route
     * assignments, transitioning them from any non-active (e.g. STAGED) state to {@code ACTIVE}.
     * Honors optimistic concurrency on the connection row via {@code expectedRevision}; returns
     * false when the revision no longer matches (retry with fresh state).
     */
    boolean activateConnection(String connectionId, int expectedRevision);

    int deleteConnection(String id);

    void saveSecretRef(LlmConnectionSecretRefDto ref);

    List<LlmConnectionSecretRefDto> listSecretRefs(String connectionId);

    int deleteSecretRefs(String connectionId);

    void saveModelProfile(ModelProfileDto profile);

    Optional<ModelProfileDto> findModelProfile(String id);

    List<ModelProfileDto> listModelProfilesByConnection(String connectionId);

    boolean updateModelProfile(ModelProfileDto profile, int expectedRevision);

    int deleteModelProfile(String id);

    void saveRouteAssignment(ModelRouteAssignmentDto assignment);

    Optional<ModelRouteAssignmentDto> findRouteAssignment(String id);

    List<ModelRouteAssignmentDto> listRoutesByScope(String scopeType, String scopeId);

    List<ModelRouteAssignmentDto> listRoutesByTarget(String targetKind, String targetId);

    boolean updateRouteAssignment(ModelRouteAssignmentDto assignment, int expectedRevision);

    int deleteRouteAssignment(String id);
}
