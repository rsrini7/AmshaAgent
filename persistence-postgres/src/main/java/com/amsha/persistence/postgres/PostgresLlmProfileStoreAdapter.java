package com.amsha.persistence.postgres;

import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmConnectionSecretRefDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelCacheInvalidationPort;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantStorePort;
import com.amsha.persistence.postgres.jdbi.LlmProfilePostgresDao;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.transaction.TransactionIsolationLevel;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
public class PostgresLlmProfileStoreAdapter implements LlmProfileStorePort, WorkspaceConnectionGrantStorePort {

    private final Jdbi jdbi;
    private final ModelCacheInvalidationPort cacheInvalidator;

    @Inject
    public PostgresLlmProfileStoreAdapter(Jdbi jdbi, @Nullable ModelCacheInvalidationPort cacheInvalidator) {
        this.jdbi = jdbi;
        this.cacheInvalidator = cacheInvalidator;
    }

    protected PostgresLlmProfileStoreAdapter() {
        this(null, null);
    }

    private void fireConnectionChanged(@Nullable String connectionId) {
        if (cacheInvalidator != null && connectionId != null) {
            cacheInvalidator.onConnectionChanged(connectionId);
        }
    }

    private void fireProfileChanged(@Nullable String profileId) {
        if (cacheInvalidator != null && profileId != null) {
            cacheInvalidator.onProfileChanged(profileId);
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private String toJson(List<String> list) {
        try {
            return mapper.writeValueAsString(list == null ? List.of() : list);
        } catch (Exception _) {
            return "[]";
        }
    }

    @Override
    public void saveConnection(LlmConnectionDto connection) {
        jdbi.useExtension(LlmProfilePostgresDao.class, dao -> dao.insertConnection(connection));
        fireConnectionChanged(connection.id());
    }

    @Override
    public Optional<LlmConnectionDto> findConnection(String id) {
        return jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.findConnection(id));
    }

    @Override
    public List<LlmConnectionDto> listConnectionsByScope(String scopeType, String scopeId) {
        return jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.listConnectionsByScope(scopeType, scopeId));
    }

    @Override
    public List<LlmConnectionDto> listConnectionsByCreatedBy(String createdByUserId) {
        return jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.listConnectionsByCreatedBy(createdByUserId));
    }

    @Override
    public boolean updateConnection(LlmConnectionDto connection, int expectedRevision) {
        boolean updated = jdbi.withExtension(
                        LlmProfilePostgresDao.class, dao -> dao.updateConnection(connection, expectedRevision))
                > 0;
        if (updated) {
            fireConnectionChanged(connection.id());
        }
        return updated;
    }

    @Override
    public int deleteConnection(String id) {
        int deleted = jdbi.withExtension(LlmProfilePostgresDao.class, dao -> {
            dao.deleteSecretRefs(id);
            dao.deleteModelProfileByConnection(id);
            return dao.deleteConnection(id);
        });
        if (deleted > 0) {
            fireConnectionChanged(id);
        }
        return deleted;
    }

    @Override
    public boolean activateConnection(String connectionId, int expectedRevision) {
        return jdbi.inTransaction(TransactionIsolationLevel.READ_COMMITTED, handle -> {
            LlmProfilePostgresDao dao = handle.attach(LlmProfilePostgresDao.class);
            LlmConnectionDto conn = dao.findConnection(connectionId).orElse(null);
            if (conn == null) {
                return false;
            }
            int updated = dao.activateConnection(connectionId, expectedRevision, Instant.now());
            if (updated == 0) {
                return false;
            }
            Instant now = Instant.now();
            dao.activateProfilesByConnection(connectionId, now);
            // A scope can hold multiple independent drafts. Activate only routes that reference
            // profiles owned by this connection, never every route in the scope.
            List<String> profileIds = dao.listModelProfilesByConnection(connectionId).stream()
                    .map(ModelProfileDto::id)
                    .toList();
            dao.listRoutesByScope(conn.scopeType(), conn.scopeId()).stream()
                    .filter(route -> route.orderedProfileIds().stream().anyMatch(profileIds::contains))
                    .forEach(route -> dao.updateRouteAssignment(
                            route.id(),
                            route.scopeType(),
                            route.scopeId(),
                            route.targetKind(),
                            route.targetId(),
                            toJson(route.orderedProfileIds()),
                            true,
                            now,
                            route.revision()));
            fireConnectionChanged(connectionId);
            profileIds.forEach(this::fireProfileChanged);
            return true;
        });
    }

    @Override
    public void saveSecretRef(LlmConnectionSecretRefDto ref) {
        jdbi.useExtension(LlmProfilePostgresDao.class, dao -> dao.upsertSecretRef(ref));
    }

    @Override
    public List<LlmConnectionSecretRefDto> listSecretRefs(String connectionId) {
        return jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.listSecretRefs(connectionId));
    }

    @Override
    public int deleteSecretRefs(String connectionId) {
        return jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.deleteSecretRefs(connectionId));
    }

    @Override
    public void saveModelProfile(ModelProfileDto profile) {
        jdbi.useExtension(LlmProfilePostgresDao.class, dao -> dao.insertModelProfile(profile));
        fireProfileChanged(profile.id());
    }

    @Override
    public Optional<ModelProfileDto> findModelProfile(String id) {
        return jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.findModelProfile(id));
    }

    @Override
    public List<ModelProfileDto> listModelProfilesByConnection(String connectionId) {
        return jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.listModelProfilesByConnection(connectionId));
    }

    @Override
    public boolean updateModelProfile(ModelProfileDto profile, int expectedRevision) {
        boolean updated = jdbi.withExtension(
                        LlmProfilePostgresDao.class, dao -> dao.updateModelProfile(profile, expectedRevision))
                > 0;
        if (updated) {
            fireProfileChanged(profile.id());
        }
        return updated;
    }

    @Override
    public int deleteModelProfile(String id) {
        int deleted = jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.deleteModelProfile(id));
        if (deleted > 0) {
            fireProfileChanged(id);
        }
        return deleted;
    }

    @Override
    public void saveRouteAssignment(ModelRouteAssignmentDto assignment) {
        jdbi.useExtension(
                LlmProfilePostgresDao.class,
                dao -> dao.insertRouteAssignment(
                        assignment.id(),
                        assignment.scopeType(),
                        assignment.scopeId(),
                        assignment.targetKind(),
                        assignment.targetId(),
                        toJson(assignment.orderedProfileIds()),
                        assignment.enabled(),
                        assignment.createdAt(),
                        assignment.updatedAt()));
        assignment.orderedProfileIds().forEach(this::fireProfileChanged);
    }

    @Override
    public Optional<ModelRouteAssignmentDto> findRouteAssignment(String id) {
        return jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.findRouteAssignment(id));
    }

    @Override
    public List<ModelRouteAssignmentDto> listRoutesByScope(String scopeType, String scopeId) {
        return jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.listRoutesByScope(scopeType, scopeId));
    }

    @Override
    public List<ModelRouteAssignmentDto> listRoutesByTarget(String targetKind, String targetId) {
        return jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.listRoutesByTarget(targetKind, targetId));
    }

    @Override
    public boolean updateRouteAssignment(ModelRouteAssignmentDto assignment, int expectedRevision) {
        boolean updated = jdbi.withExtension(
                        LlmProfilePostgresDao.class,
                        dao -> dao.updateRouteAssignment(
                                assignment.id(),
                                assignment.scopeType(),
                                assignment.scopeId(),
                                assignment.targetKind(),
                                assignment.targetId(),
                                toJson(assignment.orderedProfileIds()),
                                assignment.enabled(),
                                assignment.updatedAt(),
                                expectedRevision))
                > 0;
        if (updated) {
            assignment.orderedProfileIds().forEach(this::fireProfileChanged);
        }
        return updated;
    }

    @Override
    public int deleteRouteAssignment(String id) {
        return jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.deleteRouteAssignment(id));
    }

    @Override
    public void save(WorkspaceConnectionGrantDto grant) {
        jdbi.useExtension(LlmProfilePostgresDao.class, dao -> dao.upsertGrant(grant));
    }

    @Override
    public Optional<WorkspaceConnectionGrantDto> findByWorkspaceAndConnection(String workspaceId, String connectionId) {
        return jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.findGrant(workspaceId, connectionId));
    }

    @Override
    public List<WorkspaceConnectionGrantDto> listByWorkspace(String workspaceId) {
        return jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.listGrantsByWorkspace(workspaceId));
    }

    @Override
    public List<WorkspaceConnectionGrantDto> listByConnection(String connectionId) {
        return jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.listGrantsByConnection(connectionId));
    }

    @Override
    public int delete(String workspaceId, String connectionId) {
        return jdbi.withExtension(LlmProfilePostgresDao.class, dao -> dao.deleteGrant(workspaceId, connectionId));
    }
}
