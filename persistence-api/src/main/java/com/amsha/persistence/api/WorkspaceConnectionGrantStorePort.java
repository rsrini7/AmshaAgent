package com.amsha.persistence.api;

import java.util.List;
import java.util.Optional;

/**
 * Durable authorization records for workspace use of a connection.
 *
 * <p>A grant lets authorized workspace members use a {@code USER}-scoped connection without
 * revealing or copying its secret. Rotation/deletion of the underlying connection remains the
 * owner/admin's responsibility.</p>
 */
public interface WorkspaceConnectionGrantStorePort {

    void save(WorkspaceConnectionGrantDto grant);

    Optional<WorkspaceConnectionGrantDto> findByWorkspaceAndConnection(String workspaceId, String connectionId);

    List<WorkspaceConnectionGrantDto> listByWorkspace(String workspaceId);

    List<WorkspaceConnectionGrantDto> listByConnection(String connectionId);

    int delete(String workspaceId, String connectionId);
}
