package com.amsha.core.test;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.WorkspaceConnectionGrantDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantStorePort;

/**
 * Test-scoped stub for {@link WorkspaceConnectionGrantStorePort} so {@code core} CDI validation
 * succeeds without a persistence adapter on the test classpath. Returns empty/absent by default.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class MockWorkspaceConnectionGrantStorePort implements WorkspaceConnectionGrantStorePort {

    @Override
    public void save(WorkspaceConnectionGrantDto grant) {}

    @Override
    public Optional<WorkspaceConnectionGrantDto> findByWorkspaceAndConnection(String workspaceId, String connectionId) {
        return Optional.empty();
    }

    @Override
    public List<WorkspaceConnectionGrantDto> listByWorkspace(String workspaceId) {
        return List.of();
    }

    @Override
    public List<WorkspaceConnectionGrantDto> listByConnection(String connectionId) {
        return List.of();
    }

    @Override
    public int delete(String workspaceId, String connectionId) {
        return 0;
    }
}
