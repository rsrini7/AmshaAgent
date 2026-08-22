package com.amsha.persistence.fs;

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

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsLlmProfileStoreAdapter implements LlmProfileStorePort, WorkspaceConnectionGrantStorePort {

    private static final String DOMAIN = "llm-profiles";
    private static final String CONNECTIONS = "connections";
    private static final String SECRET_REFS = "secret-refs";
    private static final String PROFILES = "model-profiles";
    private static final String ROUTES = "route-assignments";
    private static final String GRANTS = "workspace-grants";

    @Inject
    FilesystemFileStore store;

    @Inject
    @Nullable
    ModelCacheInvalidationPort cacheInvalidator;

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

    private List<String> connectionPath(String id) {
        return List.of(DOMAIN, CONNECTIONS, id + ".json");
    }

    private List<String> secretRefDir(String connectionId) {
        return List.of(DOMAIN, SECRET_REFS, connectionId);
    }

    private List<String> profilePath(String id) {
        return List.of(DOMAIN, PROFILES, id + ".json");
    }

    private List<String> routePath(String id) {
        return List.of(DOMAIN, ROUTES, id + ".json");
    }

    private List<String> grantPath(String workspaceId, String connectionId) {
        return List.of(DOMAIN, GRANTS, workspaceId + "_" + connectionId + ".json");
    }

    @Override
    public synchronized void saveConnection(LlmConnectionDto connection) {
        store.write(connectionPath(connection.id()), connection);
        fireConnectionChanged(connection.id());
    }

    @Override
    public synchronized Optional<LlmConnectionDto> findConnection(String id) {
        return store.readOptional(connectionPath(id), LlmConnectionDto.class);
    }

    @Override
    public synchronized List<LlmConnectionDto> listConnectionsByScope(String scopeType, String scopeId) {
        return store.list(List.of(DOMAIN, CONNECTIONS), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, CONNECTIONS, file), LlmConnectionDto.class))
                .flatMap(Optional::stream)
                .filter(c -> scopeType.equals(c.scopeType()) && scopeId.equals(c.scopeId()))
                .toList();
    }

    @Override
    public synchronized List<LlmConnectionDto> listConnectionsByCreatedBy(String createdByUserId) {
        return store.list(List.of(DOMAIN, CONNECTIONS), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, CONNECTIONS, file), LlmConnectionDto.class))
                .flatMap(Optional::stream)
                .filter(c -> createdByUserId.equals(c.createdByUserId()))
                .toList();
    }

    @Override
    public synchronized boolean updateConnection(LlmConnectionDto connection, int expectedRevision) {
        Optional<LlmConnectionDto> existing = findConnection(connection.id());
        if (existing.isEmpty() || existing.get().revision() != expectedRevision) {
            return false;
        }
        store.write(connectionPath(connection.id()), connection);
        fireConnectionChanged(connection.id());
        return true;
    }

    @Override
    public synchronized int deleteConnection(String id) {
        if (findConnection(id).isEmpty()) return 0;
        store.deleteDirectory(secretRefDir(id));
        for (ModelProfileDto profile : listModelProfilesByConnection(id)) {
            store.delete(profilePath(profile.id()));
        }
        store.delete(connectionPath(id));
        fireConnectionChanged(id);
        return 1;
    }

    @Override
    public synchronized boolean activateConnection(String connectionId, int expectedRevision) {
        Optional<LlmConnectionDto> existing = findConnection(connectionId);
        if (existing.isEmpty() || existing.get().revision() != expectedRevision) {
            return false;
        }
        LlmConnectionDto conn = existing.get();
        Instant now = Instant.now();
        // Atomically (single synchronized block + atomic store writes) flip the connection and its
        // dependent profiles/routes from any non-active state to ACTIVE/enabled.
        LlmConnectionDto activated = new LlmConnectionDto(
                conn.id(),
                conn.scopeType(),
                conn.scopeId(),
                conn.createdByUserId(),
                conn.displayName(),
                conn.providerAdapterId(),
                conn.endpointProfileId(),
                conn.customBaseUrl(),
                conn.authScheme(),
                conn.networkProfileJson(),
                "ACTIVE",
                conn.revision() + 1,
                conn.createdAt(),
                now);
        store.write(connectionPath(conn.id()), activated);

        List<ModelProfileDto> connectionProfiles = listModelProfilesByConnection(connectionId);
        for (ModelProfileDto profile : connectionProfiles) {
            ModelProfileDto p = new ModelProfileDto(
                    profile.id(),
                    profile.connectionId(),
                    profile.modelId(),
                    profile.structuredOutputMode(),
                    profile.capabilityJson(),
                    profile.safeLimitsJson(),
                    true,
                    profile.revision() + 1,
                    profile.createdAt(),
                    now);
            store.write(profilePath(profile.id()), p);
        }
        List<String> profileIds =
                connectionProfiles.stream().map(ModelProfileDto::id).toList();
        for (ModelRouteAssignmentDto route : listRoutesByScope(conn.scopeType(), conn.scopeId())) {
            if (route.orderedProfileIds().stream().noneMatch(profileIds::contains)) {
                continue;
            }
            ModelRouteAssignmentDto r = new ModelRouteAssignmentDto(
                    route.id(),
                    route.scopeType(),
                    route.scopeId(),
                    route.targetKind(),
                    route.targetId(),
                    route.orderedProfileIds(),
                    true,
                    route.revision() + 1,
                    route.createdAt(),
                    now);
            store.write(routePath(route.id()), r);
        }
        fireConnectionChanged(connectionId);
        profileIds.forEach(this::fireProfileChanged);
        return true;
    }

    @Override
    public synchronized void saveSecretRef(LlmConnectionSecretRefDto ref) {
        store.write(List.of(DOMAIN, SECRET_REFS, ref.connectionId(), ref.slotId() + ".json"), ref);
    }

    @Override
    public synchronized List<LlmConnectionSecretRefDto> listSecretRefs(String connectionId) {
        return store.list(secretRefDir(connectionId), ".json").stream()
                .map(file -> store.readOptional(
                        List.of(DOMAIN, SECRET_REFS, connectionId, file), LlmConnectionSecretRefDto.class))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public synchronized int deleteSecretRefs(String connectionId) {
        if (!store.exists(secretRefDir(connectionId))) return 0;
        int count = listSecretRefs(connectionId).size();
        store.deleteDirectory(secretRefDir(connectionId));
        return count;
    }

    @Override
    public synchronized void saveModelProfile(ModelProfileDto profile) {
        store.write(profilePath(profile.id()), profile);
        fireProfileChanged(profile.id());
    }

    @Override
    public synchronized Optional<ModelProfileDto> findModelProfile(String id) {
        return store.readOptional(profilePath(id), ModelProfileDto.class);
    }

    @Override
    public synchronized List<ModelProfileDto> listModelProfilesByConnection(String connectionId) {
        return store.list(List.of(DOMAIN, PROFILES), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, PROFILES, file), ModelProfileDto.class))
                .flatMap(Optional::stream)
                .filter(p -> connectionId.equals(p.connectionId()))
                .toList();
    }

    @Override
    public synchronized boolean updateModelProfile(ModelProfileDto profile, int expectedRevision) {
        Optional<ModelProfileDto> existing = findModelProfile(profile.id());
        if (existing.isEmpty() || existing.get().revision() != expectedRevision) {
            return false;
        }
        store.write(profilePath(profile.id()), profile);
        fireProfileChanged(profile.id());
        return true;
    }

    @Override
    public synchronized int deleteModelProfile(String id) {
        if (findModelProfile(id).isEmpty()) return 0;
        store.delete(profilePath(id));
        fireProfileChanged(id);
        return 1;
    }

    @Override
    public synchronized void saveRouteAssignment(ModelRouteAssignmentDto assignment) {
        store.write(routePath(assignment.id()), assignment);
        assignment.orderedProfileIds().forEach(this::fireProfileChanged);
    }

    @Override
    public synchronized Optional<ModelRouteAssignmentDto> findRouteAssignment(String id) {
        return store.readOptional(routePath(id), ModelRouteAssignmentDto.class);
    }

    @Override
    public synchronized List<ModelRouteAssignmentDto> listRoutesByScope(String scopeType, String scopeId) {
        List<ModelRouteAssignmentDto> all = listAllRoutes();
        if (scopeType == null || scopeId == null) return all;
        return all.stream()
                .filter(r -> scopeType.equals(r.scopeType()) && scopeId.equals(r.scopeId()))
                .toList();
    }

    @Override
    public synchronized List<ModelRouteAssignmentDto> listRoutesByTarget(String targetKind, String targetId) {
        return listAllRoutes().stream()
                .filter(r -> targetKind.equals(r.targetKind())
                        && (targetId == null ? r.targetId() == null : targetId.equals(r.targetId())))
                .toList();
    }

    private List<ModelRouteAssignmentDto> listAllRoutes() {
        return store.list(List.of(DOMAIN, ROUTES), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, ROUTES, file), ModelRouteAssignmentDto.class))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public synchronized boolean updateRouteAssignment(ModelRouteAssignmentDto assignment, int expectedRevision) {
        Optional<ModelRouteAssignmentDto> existing = findRouteAssignment(assignment.id());
        if (existing.isEmpty() || existing.get().revision() != expectedRevision) {
            return false;
        }
        store.write(routePath(assignment.id()), assignment);
        assignment.orderedProfileIds().forEach(this::fireProfileChanged);
        return true;
    }

    @Override
    public synchronized int deleteRouteAssignment(String id) {
        if (findRouteAssignment(id).isEmpty()) return 0;
        store.delete(routePath(id));
        return 1;
    }

    @Override
    public synchronized void save(WorkspaceConnectionGrantDto grant) {
        store.write(grantPath(grant.workspaceId(), grant.connectionId()), grant);
    }

    @Override
    public synchronized Optional<WorkspaceConnectionGrantDto> findByWorkspaceAndConnection(
            String workspaceId, String connectionId) {
        return store.readOptional(grantPath(workspaceId, connectionId), WorkspaceConnectionGrantDto.class);
    }

    @Override
    public synchronized List<WorkspaceConnectionGrantDto> listByWorkspace(String workspaceId) {
        return store.list(List.of(DOMAIN, GRANTS), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, GRANTS, file), WorkspaceConnectionGrantDto.class))
                .flatMap(Optional::stream)
                .filter(g -> workspaceId.equals(g.workspaceId()))
                .toList();
    }

    @Override
    public synchronized List<WorkspaceConnectionGrantDto> listByConnection(String connectionId) {
        return store.list(List.of(DOMAIN, GRANTS), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, GRANTS, file), WorkspaceConnectionGrantDto.class))
                .flatMap(Optional::stream)
                .filter(g -> connectionId.equals(g.connectionId()))
                .toList();
    }

    @Override
    public synchronized int delete(String workspaceId, String connectionId) {
        if (findByWorkspaceAndConnection(workspaceId, connectionId).isEmpty()) return 0;
        store.delete(grantPath(workspaceId, connectionId));
        return 1;
    }
}
