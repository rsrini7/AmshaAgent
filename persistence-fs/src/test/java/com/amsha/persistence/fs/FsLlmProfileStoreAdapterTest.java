package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmConnectionSecretRefDto;
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
}
