package com.amsha.core.test;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmConnectionSecretRefDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;

/**
 * Test-scoped stub for {@link LlmProfileStorePort} so {@code core} CDI validation succeeds without a
 * persistence adapter on the test classpath. Returns empty/absent by default.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class MockLlmProfileStorePort implements LlmProfileStorePort {

    @Override
    public void saveConnection(LlmConnectionDto connection) {}

    @Override
    public Optional<LlmConnectionDto> findConnection(String id) {
        return Optional.empty();
    }

    @Override
    public List<LlmConnectionDto> listConnectionsByScope(String scopeType, String scopeId) {
        return List.of();
    }

    @Override
    public List<LlmConnectionDto> listConnectionsByCreatedBy(String createdByUserId) {
        return List.of();
    }

    @Override
    public boolean updateConnection(LlmConnectionDto connection, int expectedRevision) {
        return false;
    }

    @Override
    public int deleteConnection(String id) {
        return 0;
    }

    @Override
    public boolean activateConnection(String connectionId, int expectedRevision) {
        return false;
    }

    @Override
    public void saveSecretRef(LlmConnectionSecretRefDto ref) {}

    @Override
    public List<LlmConnectionSecretRefDto> listSecretRefs(String connectionId) {
        return List.of();
    }

    @Override
    public int deleteSecretRefs(String connectionId) {
        return 0;
    }

    @Override
    public void saveModelProfile(ModelProfileDto profile) {}

    @Override
    public Optional<ModelProfileDto> findModelProfile(String id) {
        return Optional.empty();
    }

    @Override
    public List<ModelProfileDto> listModelProfilesByConnection(String connectionId) {
        return List.of();
    }

    @Override
    public boolean updateModelProfile(ModelProfileDto profile, int expectedRevision) {
        return false;
    }

    @Override
    public int deleteModelProfile(String id) {
        return 0;
    }

    @Override
    public void saveRouteAssignment(ModelRouteAssignmentDto assignment) {}

    @Override
    public Optional<ModelRouteAssignmentDto> findRouteAssignment(String id) {
        return Optional.empty();
    }

    @Override
    public List<ModelRouteAssignmentDto> listRoutesByScope(String scopeType, String scopeId) {
        return List.of();
    }

    @Override
    public List<ModelRouteAssignmentDto> listRoutesByTarget(String targetKind, String targetId) {
        return List.of();
    }

    @Override
    public boolean updateRouteAssignment(ModelRouteAssignmentDto assignment, int expectedRevision) {
        return false;
    }

    @Override
    public int deleteRouteAssignment(String id) {
        return 0;
    }
}
