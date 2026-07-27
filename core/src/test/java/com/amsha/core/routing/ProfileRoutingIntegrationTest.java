package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.role.AgentGroupRegistry;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.scope.ActiveWorkspaceContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultKey;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmConnectionSecretRefDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantStorePort;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Integration contract tests for the O3 profile-routing cutover: in-flight credential/connection
 * rotation must not serve a stale pooled client, and workspace-scoped routing/credentials must not
 * cross workspace boundaries. Exercises the real {@link ModelProfileResolverImpl},
 * {@link ModelCacheInvalidator}, {@link ChatModelPool}, and {@link ModelProfileCredentialResolver}.
 */
class ProfileRoutingIntegrationTest {

    private static final Instant NOW = Instant.now();

    // ---- in-memory stateful store ----

    static final class InMemoryProfileStore implements LlmProfileStorePort {
        final Map<String, LlmConnectionDto> connections = new HashMap<>();
        final Map<String, ModelProfileDto> profiles = new HashMap<>();
        final Map<String, ModelRouteAssignmentDto> routes = new HashMap<>();

        @Override
        public void saveConnection(LlmConnectionDto connection) {
            connections.put(connection.id(), connection);
        }

        @Override
        public Optional<LlmConnectionDto> findConnection(String id) {
            return Optional.ofNullable(connections.get(id));
        }

        @Override
        public List<LlmConnectionDto> listConnectionsByScope(String scopeType, String scopeId) {
            return connections.values().stream()
                    .filter(c -> scopeType.equals(c.scopeType()) && scopeId.equals(c.scopeId()))
                    .toList();
        }

        @Override
        public List<LlmConnectionDto> listConnectionsByCreatedBy(String createdByUserId) {
            return connections.values().stream()
                    .filter(c -> createdByUserId.equals(c.createdByUserId()))
                    .toList();
        }

        @Override
        public boolean updateConnection(LlmConnectionDto connection, int expectedRevision) {
            LlmConnectionDto existing = connections.get(connection.id());
            if (existing == null || existing.revision() != expectedRevision) return false;
            connections.put(connection.id(), connection);
            return true;
        }

        @Override
        public int deleteConnection(String id) {
            return connections.remove(id) != null ? 1 : 0;
        }

        @Override
        public boolean activateConnection(String connectionId, int expectedRevision) {
            LlmConnectionDto c = connections.get(connectionId);
            if (c == null) return false;
            connections.put(
                    connectionId,
                    new LlmConnectionDto(
                            c.id(),
                            c.scopeType(),
                            c.scopeId(),
                            c.createdByUserId(),
                            c.displayName(),
                            c.providerAdapterId(),
                            c.endpointProfileId(),
                            c.customBaseUrl(),
                            c.authScheme(),
                            c.networkProfileJson(),
                            "ACTIVE",
                            c.revision() + 1,
                            c.createdAt(),
                            NOW));
            return true;
        }

        @Override
        public void saveSecretRef(LlmConnectionSecretRefDto ref) {}

        @Override
        public List<LlmConnectionSecretRefDto> listSecretRefs(String connectionId) {
            return List.of(new LlmConnectionSecretRefDto(connectionId, "apiKey", "ref:" + connectionId, 1, NOW, NOW));
        }

        @Override
        public int deleteSecretRefs(String connectionId) {
            return 0;
        }

        @Override
        public void saveModelProfile(ModelProfileDto profile) {
            profiles.put(profile.id(), profile);
        }

        @Override
        public Optional<ModelProfileDto> findModelProfile(String id) {
            return Optional.ofNullable(profiles.get(id));
        }

        @Override
        public List<ModelProfileDto> listModelProfilesByConnection(String connectionId) {
            return profiles.values().stream()
                    .filter(p -> connectionId.equals(p.connectionId()))
                    .toList();
        }

        @Override
        public boolean updateModelProfile(ModelProfileDto profile, int expectedRevision) {
            ModelProfileDto existing = profiles.get(profile.id());
            if (existing == null || existing.revision() != expectedRevision) return false;
            profiles.put(profile.id(), profile);
            return true;
        }

        @Override
        public int deleteModelProfile(String id) {
            return profiles.remove(id) != null ? 1 : 0;
        }

        @Override
        public void saveRouteAssignment(ModelRouteAssignmentDto assignment) {
            routes.put(assignment.id(), assignment);
        }

        @Override
        public Optional<ModelRouteAssignmentDto> findRouteAssignment(String id) {
            return Optional.ofNullable(routes.get(id));
        }

        @Override
        public List<ModelRouteAssignmentDto> listRoutesByScope(String scopeType, String scopeId) {
            return routes.values().stream()
                    .filter(r -> scopeType.equals(r.scopeType()) && scopeId.equals(r.scopeId()))
                    .toList();
        }

        @Override
        public List<ModelRouteAssignmentDto> listRoutesByTarget(String targetKind, String targetId) {
            return routes.values().stream()
                    .filter(r -> targetKind.equals(r.targetKind())
                            && (targetId == null ? r.targetId() == null : targetId.equals(r.targetId())))
                    .toList();
        }

        @Override
        public boolean updateRouteAssignment(ModelRouteAssignmentDto assignment, int expectedRevision) {
            routes.put(assignment.id(), assignment);
            return true;
        }

        @Override
        public int deleteRouteAssignment(String id) {
            return routes.remove(id) != null ? 1 : 0;
        }
    }

    static final class InMemoryGrantStore implements WorkspaceConnectionGrantStorePort {
        final Map<String, WorkspaceConnectionGrantDto> grants = new HashMap<>();

        @Override
        public void save(WorkspaceConnectionGrantDto grant) {
            grants.put(grant.workspaceId() + ":" + grant.connectionId(), grant);
        }

        @Override
        public Optional<WorkspaceConnectionGrantDto> findByWorkspaceAndConnection(
                String workspaceId, String connectionId) {
            return Optional.ofNullable(grants.get(workspaceId + ":" + connectionId));
        }

        @Override
        public List<WorkspaceConnectionGrantDto> listByWorkspace(String workspaceId) {
            return grants.values().stream()
                    .filter(g -> workspaceId.equals(g.workspaceId()))
                    .toList();
        }

        @Override
        public List<WorkspaceConnectionGrantDto> listByConnection(String connectionId) {
            return grants.values().stream()
                    .filter(g -> connectionId.equals(g.connectionId()))
                    .toList();
        }

        @Override
        public int delete(String workspaceId, String connectionId) {
            return grants.remove(workspaceId + ":" + connectionId) != null ? 1 : 0;
        }
    }

    private ModelProfileResolverImpl newResolver(LlmProfileStorePort store, WorkspaceConnectionGrantStorePort grants) {
        ModelProfileResolverImpl resolver = new ModelProfileResolverImpl();
        resolver.profileStore = store;
        resolver.grantStore = grants;
        resolver.groupRegistry = new AgentGroupRegistry();
        resolver.roleRegistry = new RoleRegistry();
        resolver.activeWorkspaceContext = new ActiveWorkspaceContext();
        return resolver;
    }

    private LlmConnectionDto conn(String id, String scopeType, String scopeId, String state, int rev) {
        return new LlmConnectionDto(
                id,
                scopeType,
                scopeId,
                "owner-1",
                "Conn " + id,
                "openai",
                "default",
                null,
                "API_KEY",
                null,
                state,
                rev,
                NOW,
                NOW);
    }

    private ModelProfileDto profile(String id, String connectionId, int rev, boolean enabled) {
        return new ModelProfileDto(id, connectionId, "gpt-4o", "NATIVE", null, null, enabled, rev, NOW, NOW);
    }

    private ModelRouteAssignmentDto route(
            String id,
            String scopeType,
            String scopeId,
            String targetKind,
            String targetId,
            String profileId,
            boolean enabled) {
        return new ModelRouteAssignmentDto(
                id, scopeType, scopeId, targetKind, targetId, List.of(profileId), enabled, 1, NOW, NOW);
    }

    // ---- tests ----

    @Test
    void rotation_reflectsStoreMutationAndInvalidatesPool() {
        InMemoryProfileStore store = new InMemoryProfileStore();
        ChatModelPool pool = new ChatModelPool();
        ModelCacheInvalidator invalidator = new ModelCacheInvalidator(pool);

        store.saveConnection(conn("conn-1", "USER", "u-1", "ACTIVE", 1));
        store.saveModelProfile(profile("p-1", "conn-1", 1, true));
        store.saveRouteAssignment(route("r-1", "USER", "u-1", "DEFAULT", null, "p-1", true));

        ModelProfileResolverImpl resolver = newResolver(store, new InMemoryGrantStore());

        // First resolution: profile is routable.
        Optional<ResolvedProfile> before = resolver.resolveProfile("p-1");
        assertTrue(before.isPresent());
        assertEquals(1, before.get().connectionRevision());

        // Simulate ModelRouter pooling: register a client keyed by the connection revision.
        String staleKey = ChatModelPool.typedKey("USER", "u-1", "conn-1", 1, "p-1", 1, "openai", "gpt-4o");
        ChatModel mockModel = mock(ChatModel.class);
        pool.getOrCreateTyped("USER", "u-1", "conn-1", 1, "p-1", 1, "openai", "gpt-4o", () -> mockModel);
        assertTrue(pool.contains(staleKey));

        // In-flight rotation: bump connection revision (old key must be evicted).
        LlmConnectionDto rotated = conn("conn-1", "USER", "u-1", "ACTIVE", 2);
        store.updateConnection(rotated, 1);
        invalidator.onConnectionChanged("conn-1");

        assertFalse(
                pool.contains(staleKey), "stale revisioned pool entry must be invalidated after connection rotation");

        // Re-resolution reflects the new revision (no stale cache at resolver layer).
        Optional<ResolvedProfile> after = resolver.resolveProfile("p-1");
        assertTrue(after.isPresent());
        assertEquals(2, after.get().connectionRevision());
    }

    @Test
    void workspaceRouting_isolation_noCrossing() throws Exception {
        InMemoryProfileStore store = new InMemoryProfileStore();
        store.saveConnection(conn("conn-a", "WORKSPACE", "ws-A", "ACTIVE", 1));
        store.saveModelProfile(profile("p-a", "conn-a", 1, true));
        store.saveRouteAssignment(route("r-a", "WORKSPACE", "ws-A", "DEFAULT", null, "p-a", true));
        // A distinct workspace with no routes of its own.
        store.saveConnection(conn("conn-b", "WORKSPACE", "ws-B", "ACTIVE", 1));

        InMemoryGrantStore grants = new InMemoryGrantStore();
        grants.save(new WorkspaceConnectionGrantDto("ws-A", "conn-a", "owner-1", "MEMBER_USE", "ACTIVE", NOW, NOW));

        ModelProfileResolverImpl resolver = newResolver(store, grants);

        // Actor in ws-A resolves the ws-A workspace DEFAULT route.
        RouteResolution wsAResult = WorkspaceContext.callWithActiveWorkspace("ws-A", () -> resolver.resolve("coder"));
        assertTrue(
                wsAResult.chain() != null && !wsAResult.chain().isEmpty(),
                "ws-A actor must resolve its own workspace route");
        assertEquals("workspace-default", wsAResult.precedenceTier());

        // Actor in ws-B must NOT cross into ws-A's workspace route.
        RouteResolution wsBResult = WorkspaceContext.callWithActiveWorkspace("ws-B", () -> resolver.resolve("coder"));
        assertTrue(wsBResult.chain().isEmpty(), "ws-B actor must not resolve ws-A's workspace-scoped route");
    }

    @Test
    void workspaceCredential_grantRequired_noCrossWorkspace() throws Exception {
        InMemoryProfileStore store = new InMemoryProfileStore();
        store.saveConnection(conn("conn-a", "WORKSPACE", "ws-A", "ACTIVE", 1));
        store.saveSecretRef(new LlmConnectionSecretRefDto("conn-a", "apiKey", "ref:conn-a", 1, NOW, NOW));

        InMemoryGrantStore grants = new InMemoryGrantStore();
        // Only ws-A is granted conn-a.
        grants.save(new WorkspaceConnectionGrantDto("ws-A", "conn-a", "owner-1", "MEMBER_USE", "ACTIVE", NOW, NOW));

        SecretVault vault = Mockito.mock(SecretVault.class);
        Mockito.when(vault.getWorkspace(
                        ArgumentMatchers.eq("ref:conn-a"), ArgumentMatchers.eq("ws-A"), ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(new VaultKey("sk-ws-a".getBytes())));

        ModelProfileCredentialResolver credentialResolver = new ModelProfileCredentialResolver();
        credentialResolver.vault = vault;
        credentialResolver.profileStore = store;
        credentialResolver.grantStore = grants;
        credentialResolver.activeWorkspaceContext = new ActiveWorkspaceContext();

        LlmConnectionDto wsConn = conn("conn-a", "WORKSPACE", "ws-A", "ACTIVE", 1);

        // ws-A actor: grant present + in workspace -> credential resolved.
        Optional<ResolvedCredential> granted =
                WorkspaceContext.callWithActiveWorkspace("ws-A", () -> credentialResolver.resolve(wsConn, "member-a"));
        assertTrue(granted.isPresent(), "ws-A member with active grant must resolve credential");

        // ws-B actor: no grant for (ws-B, conn-a) -> must NOT receive ws-A's credential.
        Optional<ResolvedCredential> crossed =
                WorkspaceContext.callWithActiveWorkspace("ws-B", () -> credentialResolver.resolve(wsConn, "member-b"));
        assertFalse(crossed.isPresent(), "ws-B actor must not cross into ws-A's granted connection");
    }

    @Test
    void workspaceCredential_stagedGrantRejected() throws Exception {
        InMemoryProfileStore store = new InMemoryProfileStore();
        store.saveConnection(conn("conn-a", "WORKSPACE", "ws-A", "ACTIVE", 1));
        store.saveSecretRef(new LlmConnectionSecretRefDto("conn-a", "apiKey", "ref:conn-a", 1, NOW, NOW));

        InMemoryGrantStore grants = new InMemoryGrantStore();
        // Grant exists but is STAGED, not ACTIVE.
        grants.save(new WorkspaceConnectionGrantDto("ws-A", "conn-a", "owner-1", "MEMBER_USE", "STAGED", NOW, NOW));

        SecretVault vault = Mockito.mock(SecretVault.class);
        ModelProfileCredentialResolver credentialResolver = new ModelProfileCredentialResolver();
        credentialResolver.vault = vault;
        credentialResolver.profileStore = store;
        credentialResolver.grantStore = grants;
        credentialResolver.activeWorkspaceContext = new ActiveWorkspaceContext();

        LlmConnectionDto wsConn = conn("conn-a", "WORKSPACE", "ws-A", "ACTIVE", 1);
        Optional<ResolvedCredential> result =
                WorkspaceContext.callWithActiveWorkspace("ws-A", () -> credentialResolver.resolve(wsConn, "member-a"));
        assertFalse(result.isPresent(), "STAGED grant must not authorize credential use");
        // Vault must never be consulted for a non-active grant.
        Mockito.verify(vault, Mockito.never())
                .getWorkspace(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }
}
