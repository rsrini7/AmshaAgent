package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmConnectionSecretRefDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantStorePort;
import com.amsha.persistence.postgres.testutil.FlywayEnabledTestProfile;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
@QuarkusTestResource(PostgresTestResource.class)
@SuppressWarnings("java:S1192")
class PostgresLlmProfileStoreAdapterTest {

    @Inject
    LlmProfileStorePort store;

    @Inject
    WorkspaceConnectionGrantStorePort grantStore;

    @Inject
    Jdbi jdbi;

    @BeforeEach
    void cleanLlmProfileTables() {
        jdbi.useHandle(h -> {
            for (String table : List.of(
                    "model_route_assignment",
                    "llm_connection_secret_ref",
                    "model_profile",
                    "llm_connection",
                    "workspace_connection_grant")) {
                h.execute("DELETE FROM " + table);
            }
        });
    }

    private static LlmConnectionDto connection(String id, String scopeType, String scopeId) {
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

    @Test
    void connectionCrudAndScopeListing() {
        String prefix = "pg-" + UUID.randomUUID();
        LlmConnectionDto conn = connection(prefix + "-c1", "USER", prefix + "-user-1");
        store.saveConnection(conn);
        Optional<LlmConnectionDto> found = store.findConnection(conn.id());
        assertTrue(found.isPresent());
        assertEquals("openai", found.get().providerAdapterId());

        List<LlmConnectionDto> byScope = store.listConnectionsByScope("USER", prefix + "-user-1");
        assertEquals(1, byScope.size());
        List<LlmConnectionDto> byOwner = store.listConnectionsByCreatedBy("owner-1");
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
        assertTrue(store.updateConnection(updated, 1));
        assertFalse(store.updateConnection(updated, 1), "stale revision must fail");
        assertEquals("Renamed", store.findConnection(conn.id()).get().displayName());

        assertEquals(1, store.deleteConnection(conn.id()));
        assertTrue(store.findConnection(conn.id()).isEmpty());
    }

    @Test
    void secretRefAndProfileAndRouteContract() {
        String prefix = "pg-" + UUID.randomUUID();
        store.saveConnection(connection(prefix + "-c2", "USER", prefix + "-user-2"));
        Instant now = Instant.now();
        store.saveSecretRef(new LlmConnectionSecretRefDto(prefix + "-c2", "apiKey", "USER:user-2:ref", 1, now, now));
        assertEquals(1, store.listSecretRefs(prefix + "-c2").size());
        assertEquals(1, store.deleteSecretRefs(prefix + "-c2"));
        assertTrue(store.listSecretRefs(prefix + "-c2").isEmpty());

        store.saveModelProfile(
                new ModelProfileDto(prefix + "-p1", prefix + "-c2", "gpt-4o", "NATIVE", null, null, true, 1, now, now));
        assertEquals(1, store.listModelProfilesByConnection(prefix + "-c2").size());

        store.saveRouteAssignment(new ModelRouteAssignmentDto(
                prefix + "-r1",
                "USER",
                prefix + "-user-2",
                "DEFAULT",
                null,
                List.of(prefix + "-p1"),
                true,
                1,
                now,
                now));
        assertEquals(1, store.listRoutesByScope("USER", prefix + "-user-2").size());
        assertEquals(1, store.listRoutesByTarget("DEFAULT", null).size());
    }

    @Test
    void workspaceGrantContract() {
        grantStore.save(new WorkspaceConnectionGrantDto(
                "ws-1", "c-shared", "owner-1", "MEMBER_USE", "ACTIVE", Instant.now(), Instant.now()));
        assertTrue(grantStore.findByWorkspaceAndConnection("ws-1", "c-shared").isPresent());
        assertEquals(1, grantStore.listByWorkspace("ws-1").size());
        assertEquals(1, grantStore.listByConnection("c-shared").size());
        assertEquals(1, grantStore.delete("ws-1", "c-shared"));
        assertTrue(grantStore.findByWorkspaceAndConnection("ws-1", "c-shared").isEmpty());
    }

    /**
     * Activation is the one write path here that spans several tables in a single transaction: it flips the
     * connection, enables the profiles it owns, and enables only the routes that reference one of those profiles.
     * A route in the same scope pointing at an unrelated profile must be left alone — a scope can hold independent
     * drafts, and enabling every route in the scope would activate them.
     */
    @Test
    void activateConnectionFlipsConnectionProfilesAndRoutesToActive() {
        String prefix = "pg-" + UUID.randomUUID();
        LlmConnectionDto staged = new LlmConnectionDto(
                prefix + "-c3",
                "USER",
                prefix + "-user-3",
                "owner-1",
                "Conn",
                "openai",
                "default",
                null,
                "API_KEY",
                null,
                "STAGED",
                1,
                Instant.now(),
                Instant.now());
        store.saveConnection(staged);

        Instant now = Instant.now();
        store.saveModelProfile(new ModelProfileDto(
                prefix + "-p2", prefix + "-c3", "gpt-4o", "NATIVE", null, null, false, 1, now, now));
        store.saveRouteAssignment(new ModelRouteAssignmentDto(
                prefix + "-r2",
                "USER",
                prefix + "-user-3",
                "ACTIVE_WORKSPACE",
                null,
                List.of(prefix + "-p2"),
                false,
                1,
                now,
                now));
        store.saveRouteAssignment(new ModelRouteAssignmentDto(
                prefix + "-r-unrelated",
                "USER",
                prefix + "-user-3",
                "DEFAULT",
                null,
                List.of(prefix + "-other-profile"),
                false,
                1,
                now,
                now));

        assertTrue(store.activateConnection(prefix + "-c3", 1));

        assertEquals("ACTIVE", store.findConnection(prefix + "-c3").get().state());
        assertTrue(store.listModelProfilesByConnection(prefix + "-c3").get(0).enabled());
        assertTrue(store.findRouteAssignment(prefix + "-r2").orElseThrow().enabled());
        assertFalse(
                store.findRouteAssignment(prefix + "-r-unrelated").orElseThrow().enabled());

        assertFalse(store.activateConnection(prefix + "-c3", 1), "stale revision must fail");
    }

    @Test
    void activateConnectionRejectsAMissingConnection() {
        assertFalse(store.activateConnection("missing-" + UUID.randomUUID(), 1));
    }

    @Test
    void modelProfileUpdateAndDelete() {
        String prefix = "pg-" + UUID.randomUUID();
        store.saveConnection(connection(prefix + "-c4", "USER", prefix + "-user-4"));
        Instant now = Instant.now();
        store.saveModelProfile(
                new ModelProfileDto(prefix + "-p4", prefix + "-c4", "gpt-4o", "NATIVE", null, null, true, 1, now, now));
        assertTrue(store.findModelProfile(prefix + "-p4").isPresent());

        ModelProfileDto renamed = new ModelProfileDto(
                prefix + "-p4", prefix + "-c4", "gpt-4o-mini", "NATIVE", null, null, false, 2, now, Instant.now());
        assertTrue(store.updateModelProfile(renamed, 1));

        ModelProfileDto after = store.findModelProfile(prefix + "-p4").orElseThrow();
        assertEquals("gpt-4o-mini", after.modelId());
        assertFalse(after.enabled());

        assertFalse(store.updateModelProfile(renamed, 1), "stale revision must fail");

        assertEquals(1, store.deleteModelProfile(prefix + "-p4"));
        assertTrue(store.findModelProfile(prefix + "-p4").isEmpty());
        assertEquals(0, store.deleteModelProfile(prefix + "-p4"), "already deleted");
    }

    @Test
    void routeAssignmentUpdateAndDelete() {
        String prefix = "pg-" + UUID.randomUUID();
        Instant now = Instant.now();
        store.saveRouteAssignment(new ModelRouteAssignmentDto(
                prefix + "-r4",
                "USER",
                prefix + "-user-4",
                "DEFAULT",
                null,
                List.of(prefix + "-p4"),
                true,
                1,
                now,
                now));
        assertTrue(store.findRouteAssignment(prefix + "-r4").isPresent());

        ModelRouteAssignmentDto disabled = new ModelRouteAssignmentDto(
                prefix + "-r4",
                "USER",
                prefix + "-user-4",
                "DEFAULT",
                null,
                List.of(prefix + "-p4"),
                false,
                2,
                now,
                Instant.now());
        assertTrue(store.updateRouteAssignment(disabled, 1));
        assertFalse(store.findRouteAssignment(prefix + "-r4").orElseThrow().enabled());

        assertFalse(store.updateRouteAssignment(disabled, 1), "stale revision must fail");

        assertEquals(1, store.deleteRouteAssignment(prefix + "-r4"));
        assertTrue(store.findRouteAssignment(prefix + "-r4").isEmpty());
    }

    /**
     * {@code ordered_profile_ids} is a {@code TEXT} JSON array parsed by {@code RouteRowMapper.parseList}.
     * Writing is total, reading is not: a route whose JSON is corrupt must still be readable, degrading that
     * one column to an empty list. Returning empty rather than throwing keeps the rest of the routing table
     * inspectable so an operator can repair the row.
     */
    @Test
    void aCorruptOrderedProfileIdsColumnDegradesToAnEmptyList() {
        String prefix = "pg-" + UUID.randomUUID();
        Instant now = Instant.now();
        String routeId = prefix + "-r5";
        store.saveRouteAssignment(new ModelRouteAssignmentDto(
                routeId, "USER", prefix + "-user-5", "DEFAULT", null, List.of(prefix + "-p5"), true, 1, now, now));

        jdbi.useHandle(h -> h.execute(
                "UPDATE model_route_assignment SET ordered_profile_ids = ? WHERE id = ?", "[not json", routeId));

        ModelRouteAssignmentDto read = store.findRouteAssignment(routeId).orElseThrow();

        assertTrue(read.orderedProfileIds().isEmpty(), "an unparseable column must degrade, not throw");
        // The sibling columns are untouched, which is what proves the degradation is per-column.
        assertEquals("USER", read.scopeType());
        assertTrue(read.enabled());
    }
}
