package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmConnectionSecretRefDto;
import com.amsha.persistence.api.ModelCacheInvalidationPort;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantStorePort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S5443") // Test-only temp directory.
class FsLlmProfileStoreAdapterTest {

    private Path tempDir;
    private FsLlmProfileStoreAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-fs-llm-test-");
        FsStoreProperties props = new FsStoreProperties() {
            @Override
            public String dataDir() {
                return tempDir.toString();
            }

            @Override
            public int lockTimeoutSeconds() {
                return 5;
            }

            @Override
            public String luceneIndexDir() {
                return "lucene-index";
            }

            @Override
            public int vectorDimension() {
                return 768;
            }
        };
        FilesystemFileStore fileStore = new FilesystemFileStore(props);
        fileStore.ensureDataDir();
        adapter = new FsLlmProfileStoreAdapter();
        adapter.store = fileStore;
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) return;
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException _) {
                    // best-effort cleanup
                }
            });
        }
    }

    private LlmConnectionDto sampleConnection(String id, String scopeType, String scopeId) {
        Instant now = Instant.now();
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
                "ACTIVE",
                1,
                now,
                now);
    }

    private ModelProfileDto sampleProfile(String id, String connectionId) {
        Instant now = Instant.now();
        return new ModelProfileDto(id, connectionId, "gpt-4o", "NATIVE", null, null, true, 1, now, now);
    }

    private ModelRouteAssignmentDto sampleRoute(String id, String scopeType, String scopeId) {
        Instant now = Instant.now();
        return new ModelRouteAssignmentDto(id, scopeType, scopeId, "DEFAULT", null, List.of("p-1"), true, 1, now, now);
    }

    /**
     * Records the cache-invalidation callbacks in order. The adapter only calls the port when it is
     * bound (the field is {@code @Nullable}) and only for mutations that actually changed durable
     * state, so the recorded sequence is the observable contract of the two private
     * {@code fireConnectionChanged}/{@code fireProfileChanged} helpers.
     */
    private static final class RecordingInvalidator implements ModelCacheInvalidationPort {

        private final List<String> events = new ArrayList<>();

        @Override
        public void onConnectionChanged(String connectionId) {
            events.add("connection:" + connectionId);
        }

        @Override
        public void onProfileChanged(String profileId) {
            events.add("profile:" + profileId);
        }
    }

    @Test
    void connectionCrudAndScopeListing() {
        LlmConnectionDto conn = sampleConnection("c-1", "USER", "user-1");
        adapter.saveConnection(conn);
        Optional<LlmConnectionDto> found = adapter.findConnection("c-1");
        assertTrue(found.isPresent());
        assertEquals("openai", found.get().providerAdapterId());

        List<LlmConnectionDto> byScope = adapter.listConnectionsByScope("USER", "user-1");
        assertEquals(1, byScope.size());
        List<LlmConnectionDto> byOwner = adapter.listConnectionsByCreatedBy("owner-1");
        assertEquals(1, byOwner.size());

        LlmConnectionDto updated = new LlmConnectionDto(
                conn.id(),
                conn.scopeType(),
                conn.scopeId(),
                conn.createdByUserId(),
                "Renamed",
                conn.providerAdapterId(),
                conn.endpointProfileId(),
                conn.customBaseUrl(),
                conn.authScheme(),
                conn.networkProfileJson(),
                conn.state(),
                2,
                conn.createdAt(),
                Instant.now());
        assertTrue(adapter.updateConnection(updated, 1));
        assertEquals("Renamed", adapter.findConnection("c-1").get().displayName());
        assertFalse(adapter.updateConnection(updated, 1), "stale revision must fail");
    }

    @Test
    void secretRefsAreScopedToConnection() {
        adapter.saveConnection(sampleConnection("c-2", "USER", "user-2"));
        Instant now = Instant.now();
        adapter.saveSecretRef(new LlmConnectionSecretRefDto("c-2", "apiKey", "USER:user-2:ref-1", 1, now, now));
        List<LlmConnectionSecretRefDto> refs = adapter.listSecretRefs("c-2");
        assertEquals(1, refs.size());
        assertEquals("apiKey", refs.get(0).slotId());
        assertEquals(1, adapter.deleteSecretRefs("c-2"));
        assertTrue(adapter.listSecretRefs("c-2").isEmpty());
    }

    @Test
    void modelProfileCrudAndRouteTargets() {
        adapter.saveConnection(sampleConnection("c-3", "USER", "user-3"));
        adapter.saveModelProfile(sampleProfile("p-1", "c-3"));
        assertTrue(adapter.listModelProfilesByConnection("c-3").size() == 1);

        ModelRouteAssignmentDto route = sampleRoute("r-1", "USER", "user-3");
        adapter.saveRouteAssignment(route);
        List<ModelRouteAssignmentDto> byScope = adapter.listRoutesByScope("USER", "user-3");
        assertEquals(1, byScope.size());
        List<ModelRouteAssignmentDto> byTarget = adapter.listRoutesByTarget("DEFAULT", null);
        assertEquals(1, byTarget.size());

        ModelRouteAssignmentDto reordered = new ModelRouteAssignmentDto(
                route.id(),
                route.scopeType(),
                route.scopeId(),
                route.targetKind(),
                route.targetId(),
                List.of("p-1", "p-2"),
                route.enabled(),
                2,
                route.createdAt(),
                Instant.now());
        assertTrue(adapter.updateRouteAssignment(reordered, 1));
        assertEquals(
                2, adapter.findRouteAssignment("r-1").get().orderedProfileIds().size());
    }

    @Test
    void workspaceGrantLifecycle() {
        WorkspaceConnectionGrantStorePort grantPort = adapter;
        grantPort.save(new WorkspaceConnectionGrantDto(
                "ws-1", "c-shared", "owner-1", "MEMBER_USE", "ACTIVE", Instant.now(), Instant.now()));
        assertTrue(grantPort.findByWorkspaceAndConnection("ws-1", "c-shared").isPresent());
        assertEquals(1, grantPort.listByWorkspace("ws-1").size());
        assertEquals(1, grantPort.listByConnection("c-shared").size());
        assertEquals(1, grantPort.delete("ws-1", "c-shared"));
        assertTrue(grantPort.findByWorkspaceAndConnection("ws-1", "c-shared").isEmpty());
    }

    @Test
    void activateConnection_flipsStagedConnectionProfilesAndRoutesToActive() {
        Instant seeded = Instant.now();
        adapter.saveConnection(new LlmConnectionDto(
                "c-act",
                "USER",
                "user-act",
                "owner-1",
                "Conn c-act",
                "openai",
                "default",
                null,
                "API_KEY",
                null,
                "STAGED",
                1,
                seeded,
                seeded));
        Instant now = Instant.now();
        adapter.saveModelProfile(
                new ModelProfileDto("p-act", "c-act", "gpt-4o", "NATIVE", null, null, false, 1, now, now));
        adapter.saveRouteAssignment(new ModelRouteAssignmentDto(
                "r-act", "USER", "user-act", "ACTIVE_WORKSPACE", null, List.of("p-act"), false, 1, now, now));
        adapter.saveRouteAssignment(new ModelRouteAssignmentDto(
                "r-other", "USER", "user-act", "DEFAULT", null, List.of("p-other"), false, 1, now, now));
        assertTrue(adapter.activateConnection("c-act", 1));

        assertEquals("ACTIVE", adapter.findConnection("c-act").get().state());
        assertTrue(adapter.listModelProfilesByConnection("c-act").get(0).enabled());
        assertTrue(adapter.findRouteAssignment("r-act").orElseThrow().enabled());
        assertFalse(adapter.findRouteAssignment("r-other").orElseThrow().enabled());

        // Stale revision must fail.
        assertFalse(adapter.activateConnection("c-act", 1));
        assertEquals(1, adapter.deleteConnection("c-act"));
    }

    @Test
    void deleteConnectionCascadesProfilesAndSecretRefs() {
        adapter.saveConnection(sampleConnection("c-9", "USER", "user-9"));
        Instant now = Instant.now();
        adapter.saveSecretRef(new LlmConnectionSecretRefDto("c-9", "apiKey", "ref", 1, now, now));
        adapter.saveModelProfile(sampleProfile("p-9", "c-9"));
        assertEquals(1, adapter.deleteConnection("c-9"));
        assertTrue(adapter.findConnection("c-9").isEmpty());
        assertTrue(adapter.listSecretRefs("c-9").isEmpty());
        assertTrue(adapter.listModelProfilesByConnection("c-9").isEmpty());
    }

    @Test
    void modelProfileFindUpdateAndDelete() {
        adapter.saveConnection(sampleConnection("c-up", "USER", "user-up"));
        assertTrue(adapter.findModelProfile("p-absent").isEmpty());

        adapter.saveModelProfile(sampleProfile("p-up", "c-up"));
        assertEquals("gpt-4o", adapter.findModelProfile("p-up").orElseThrow().modelId());

        // Optimistic locking: a stale expected revision is rejected and leaves the row untouched.
        ModelProfileDto stale = new ModelProfileDto(
                "p-up", "c-up", "gpt-4.1", "NATIVE", null, null, true, 99, Instant.now(), Instant.now());
        assertFalse(adapter.updateModelProfile(stale, 42), "stale revision must be rejected");
        assertEquals("gpt-4o", adapter.findModelProfile("p-up").orElseThrow().modelId());

        // An id that was never persisted is rejected through the same guard.
        ModelProfileDto ghost = new ModelProfileDto(
                "p-ghost", "c-up", "gpt-4o", "NATIVE", null, null, true, 1, Instant.now(), Instant.now());
        assertFalse(adapter.updateModelProfile(ghost, 1), "updating a never-saved profile must be rejected");

        ModelProfileDto renamed = new ModelProfileDto(
                "p-up", "c-up", "gpt-4.1", "NATIVE", null, null, true, 2, Instant.now(), Instant.now());
        assertTrue(adapter.updateModelProfile(renamed, 1));
        assertEquals("gpt-4.1", adapter.findModelProfile("p-up").orElseThrow().modelId());

        assertEquals(0, adapter.deleteModelProfile("p-never-saved"));
        assertEquals(1, adapter.deleteModelProfile("p-up"));
        assertTrue(adapter.findModelProfile("p-up").isEmpty());
    }

    @Test
    void routeAssignmentUpdateAndDelete() {
        adapter.saveConnection(sampleConnection("c-rt", "USER", "user-rt"));
        adapter.saveModelProfile(sampleProfile("p-rt", "c-rt"));
        adapter.saveRouteAssignment(sampleRoute("r-rt", "USER", "user-rt"));

        ModelRouteAssignmentDto stale = new ModelRouteAssignmentDto(
                "r-rt",
                "USER",
                "user-rt",
                "DEFAULT",
                null,
                List.of("p-rt", "p-2"),
                true,
                7,
                Instant.now(),
                Instant.now());
        assertFalse(adapter.updateRouteAssignment(stale, 99), "stale revision must be rejected");
        assertEquals(
                1,
                adapter.findRouteAssignment("r-rt")
                        .orElseThrow()
                        .orderedProfileIds()
                        .size());

        ModelRouteAssignmentDto reordered = new ModelRouteAssignmentDto(
                "r-rt",
                "USER",
                "user-rt",
                "DEFAULT",
                null,
                List.of("p-rt", "p-2"),
                true,
                2,
                Instant.now(),
                Instant.now());
        assertTrue(adapter.updateRouteAssignment(reordered, 1));
        assertEquals(
                2,
                adapter.findRouteAssignment("r-rt")
                        .orElseThrow()
                        .orderedProfileIds()
                        .size());

        assertFalse(adapter.updateRouteAssignment(reordered, 1), "an unknown id must be rejected");

        assertEquals(0, adapter.deleteRouteAssignment("r-never-saved"));
        assertEquals(1, adapter.deleteRouteAssignment("r-rt"));
        assertTrue(adapter.findRouteAssignment("r-rt").isEmpty());
    }

    @Test
    void cacheInvalidationPortReceivesEveryDurableMutation() {
        RecordingInvalidator invalidator = new RecordingInvalidator();
        adapter.cacheInvalidator = invalidator;

        adapter.saveConnection(sampleConnection("c-inv", "USER", "user-inv"));
        adapter.saveModelProfile(sampleProfile("p-inv", "c-inv"));
        assertEquals(List.of("connection:c-inv", "profile:p-inv"), invalidator.events);

        // The accepted update path fires the profile callback too.
        assertTrue(adapter.updateModelProfile(
                new ModelProfileDto(
                        "p-inv", "c-inv", "gpt-4o", "NATIVE", null, null, true, 2, Instant.now(), Instant.now()),
                1));
        assertEquals("profile:p-inv", invalidator.events.get(2));

        // Rejected writes never reached the store, so they must not flush the cache either.
        ModelProfileDto staleProfile = new ModelProfileDto(
                "p-inv", "c-inv", "gpt-4o", "NATIVE", null, null, true, 42, Instant.now(), Instant.now());
        assertFalse(adapter.updateModelProfile(staleProfile, 7));
        ModelRouteAssignmentDto staleRoute = new ModelRouteAssignmentDto(
                "r-inv", "USER", "user-inv", "DEFAULT", null, List.of("p-inv"), true, 42, Instant.now(), Instant.now());
        assertFalse(adapter.updateRouteAssignment(staleRoute, 7));
        assertEquals(3, invalidator.events.size(), "rejected writes must not invalidate the cache");

        // A delete of a missing row is a no-op, so it must not fire either.
        assertEquals(0, adapter.deleteModelProfile("p-absent"));
        assertEquals(3, invalidator.events.size(), "deleting a missing profile must not invalidate the cache");

        assertEquals(1, adapter.deleteModelProfile("p-inv"));
        assertEquals("profile:p-inv", invalidator.events.get(3));

        assertEquals(1, adapter.deleteConnection("c-inv"));
        assertEquals("connection:c-inv", invalidator.events.get(4));
    }
}
