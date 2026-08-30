package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmConnectionSecretRefDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModalitySeederIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

    private InMemoryStore store;
    private InMemoryVault vault;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        vault = new InMemoryVault();
        vault.storeSystemKey(VaultConstants.LLM_API_KEY, "test-api-key".getBytes(StandardCharsets.UTF_8));
        vault.storeSystemKey(VaultConstants.LLM_PROVIDER_KEY, "omlx".getBytes(StandardCharsets.UTF_8));
        vault.storeSystemKey(VaultConstants.LLM_MODEL_KEY, "Qwen3.8-27B-4bit".getBytes(StandardCharsets.UTF_8));
    }

    private ModalityRouteSeeder enabledSeeder() {
        ModalityRouteSeeder seeder = new ModalityRouteSeeder(vault, store);
        seeder.seederEnabled = true;
        seeder.profileRoutingEnabled = true;
        return seeder;
    }

    private void runLegacyMigration() {
        new SystemProfileMigration(vault, store).migrateIfNeeded();
    }

    @Test
    void seederDisabledLeavesLegacyRoute() {
        runLegacyMigration();

        ModalityRouteSeeder seeder = new ModalityRouteSeeder(vault, store);
        seeder.seederEnabled = false;
        seeder.profileRoutingEnabled = true;
        seeder.seedIfNeeded();

        List<ModelRouteAssignmentDto> routes = store.listRoutesByScope("SYSTEM", "SYSTEM");
        assertEquals(1, routes.size());
        assertEquals("sys-legacy-route", routes.get(0).id());
    }

    @Test
    void profileRoutingDisabledLeavesPersistedConfigurationUntouched() {
        runLegacyMigration();
        assertEquals(1, store.listRoutesByScope("SYSTEM", "SYSTEM").size());

        ModalityRouteSeeder seeder = new ModalityRouteSeeder(vault, store);
        seeder.seederEnabled = true;
        seeder.profileRoutingEnabled = false;
        seeder.seedIfNeeded();

        List<ModelRouteAssignmentDto> routes = store.listRoutesByScope("SYSTEM", "SYSTEM");
        assertEquals(1, routes.size(), "Profile routing off: seeded routes would never be read");
        assertEquals("sys-legacy-route", routes.get(0).id());
        assertTrue(store.findConnection(ModalityRouteSeeder.CONN_ID).isEmpty());
        assertTrue(
                store.listModelProfilesByConnection(ModalityRouteSeeder.CONN_ID).isEmpty());
    }

    @Test
    void missingVaultKeyNeverDestroysAnExistingRoute() {
        runLegacyMigration();
        assertEquals(1, store.listRoutesByScope("SYSTEM", "SYSTEM").size());
        vault.removeSystemKey(VaultConstants.LLM_API_KEY);

        enabledSeeder().seedIfNeeded();

        List<ModelRouteAssignmentDto> routes = store.listRoutesByScope("SYSTEM", "SYSTEM");
        assertEquals(1, routes.size(), "Guard must run before mutation: the legacy route survives");
        assertEquals("sys-legacy-route", routes.get(0).id());
        assertTrue(store.findConnection(ModalityRouteSeeder.CONN_ID).isEmpty());
    }

    @Test
    void seederEnabledReplacesLegacyRouteWithModalityRoute() {
        runLegacyMigration();

        assertEquals(1, store.listRoutesByScope("SYSTEM", "SYSTEM").size());

        enabledSeeder().seedIfNeeded();

        assertExactlyOneSystemDefaultRoute();
        ModelRouteAssignmentDto route =
                store.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID).orElseThrow();
        assertEquals(ModalityRouteSeeder.ROUTE_ID, route.id());
        assertEquals(
                List.of(ModalityRouteSeeder.PROFILE_TEXT_ID, ModalityRouteSeeder.PROFILE_IMAGE_ID),
                route.orderedProfileIds());
        // The legacy row survives as a disabled route; convergence is by disabling, not deleting.
        assertTrue(store.findRouteAssignment("sys-legacy-route").isPresent());

        assertEquals(
                2,
                store.listModelProfilesByConnection(ModalityRouteSeeder.CONN_ID).size());
        for (String pid : route.orderedProfileIds()) {
            assertTrue(store.findModelProfile(pid).isPresent());
            assertTrue(store.findModelProfile(pid).get().enabled());
        }
        long distinctConnections = route.orderedProfileIds().stream()
                .map(id -> store.findModelProfile(id).get().connectionId())
                .distinct()
                .count();
        assertEquals(1, distinctConnections, "All modality profiles must share one connection (N:1)");
    }

    @Test
    void freshDbWithSeederEnabledCreatesModalityRouteDirectly() {
        enabledSeeder().seedIfNeeded();

        runLegacyMigration();

        assertExactlyOneSystemDefaultRoute();
        assertEquals(
                ModalityRouteSeeder.ROUTE_ID,
                store.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID)
                        .orElseThrow()
                        .id());
    }

    @Test
    void repeatedStartupsConvergeToOneRoute() {
        runLegacyMigration();
        enabledSeeder().seedIfNeeded();
        int revisionAfterFirst = store.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID)
                .orElseThrow()
                .revision();

        enabledSeeder().seedIfNeeded();
        enabledSeeder().seedIfNeeded();

        assertExactlyOneSystemDefaultRoute();
        assertEquals(
                revisionAfterFirst,
                store.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID)
                        .orElseThrow()
                        .revision(),
                "A converged configuration must not churn revisions on every startup");
    }

    @Test
    void disabledPersistedRouteIsReactivated() {
        runLegacyMigration();
        enabledSeeder().seedIfNeeded();
        ModelRouteAssignmentDto seeded =
                store.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID).orElseThrow();
        assertTrue(store.updateRouteAssignment(
                new ModelRouteAssignmentDto(
                        seeded.id(),
                        seeded.scopeType(),
                        seeded.scopeId(),
                        seeded.targetKind(),
                        seeded.targetId(),
                        seeded.orderedProfileIds(),
                        false,
                        seeded.revision(),
                        seeded.createdAt(),
                        NOW),
                seeded.revision()));

        enabledSeeder().seedIfNeeded();

        assertExactlyOneSystemDefaultRoute();
        ModelRouteAssignmentDto reactivated =
                store.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID).orElseThrow();
        assertTrue(reactivated.enabled(), "Persisted-but-disabled route must be safely re-activated");
        assertEquals(ModalityRouteSeeder.ORDERED_PROFILE_IDS, reactivated.orderedProfileIds());
    }

    @Test
    void staleOrderedProfileIdsAreRepaired() {
        runLegacyMigration();
        enabledSeeder().seedIfNeeded();
        ModelRouteAssignmentDto seeded =
                store.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID).orElseThrow();
        assertTrue(store.updateRouteAssignment(
                new ModelRouteAssignmentDto(
                        seeded.id(),
                        seeded.scopeType(),
                        seeded.scopeId(),
                        seeded.targetKind(),
                        seeded.targetId(),
                        List.of(ModalityRouteSeeder.PROFILE_TEXT_ID),
                        true,
                        seeded.revision(),
                        seeded.createdAt(),
                        NOW),
                seeded.revision()));

        enabledSeeder().seedIfNeeded();

        assertExactlyOneSystemDefaultRoute();
        assertEquals(
                ModalityRouteSeeder.ORDERED_PROFILE_IDS,
                store.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID)
                        .orElseThrow()
                        .orderedProfileIds());
    }

    @Test
    void disabledProfileIsRepaired() {
        runLegacyMigration();
        enabledSeeder().seedIfNeeded();
        ModelProfileDto image =
                store.findModelProfile(ModalityRouteSeeder.PROFILE_IMAGE_ID).orElseThrow();
        assertTrue(store.updateModelProfile(
                new ModelProfileDto(
                        image.id(),
                        image.connectionId(),
                        image.modelId(),
                        image.structuredOutputMode(),
                        image.capabilityJson(),
                        image.safeLimitsJson(),
                        false,
                        image.revision(),
                        image.createdAt(),
                        NOW),
                image.revision()));

        enabledSeeder().seedIfNeeded();

        assertExactlyOneSystemDefaultRoute();
        assertTrue(
                store.findModelProfile(ModalityRouteSeeder.PROFILE_IMAGE_ID)
                        .orElseThrow()
                        .enabled(),
                "A disabled profile must be re-enabled so the route chain stays complete");
    }

    /**
     * The upgrade path: a machine that already persisted the Nemotron audio profile from an
     * earlier revision must not keep it selectable, because OMLX cannot load that architecture.
     */
    private void persistLegacyAudioProfile() {
        store.saveConnection(new LlmConnectionDto(
                ModalityRouteSeeder.CONN_ID,
                "SYSTEM",
                "SYSTEM",
                "system",
                "Shared OMLX modality connection (text/image)",
                "omlx",
                "default",
                null,
                "API_KEY",
                null,
                "ACTIVE",
                1,
                NOW,
                NOW));
        store.saveModelProfile(new ModelProfileDto(
                ModalityRouteSeeder.PROFILE_AUDIO_ID,
                ModalityRouteSeeder.CONN_ID,
                ModalityRouteSeeder.MODEL_AUDIO,
                "PROMPT_DIRECTIVE",
                "{\"supportedTypes\":[\"AUDIO\"]}",
                null,
                true,
                1,
                NOW,
                NOW));
    }

    @Test
    void persistedAudioProfileFromAnEarlierRevisionIsDisabled() {
        persistLegacyAudioProfile();

        enabledSeeder().seedIfNeeded();

        ModelProfileDto audio =
                store.findModelProfile(ModalityRouteSeeder.PROFILE_AUDIO_ID).orElseThrow();
        assertFalse(audio.enabled(), "The unloadable audio profile must be disabled, not deleted");
        assertEquals(ModalityRouteSeeder.MODEL_AUDIO, audio.modelId(), "Retirement must not rewrite the model");
        assertExactlyOneSystemDefaultRoute();
        assertTrue(
                store.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID).orElseThrow().orderedProfileIds().stream()
                        .noneMatch(ModalityRouteSeeder.PROFILE_AUDIO_ID::equals),
                "The retired profile must not appear in the active route order");
    }

    @Test
    void repeatedStartupsDoNotReEnableTheRetiredAudioProfile() {
        persistLegacyAudioProfile();
        enabledSeeder().seedIfNeeded();
        int revisionAfterFirst = store.findModelProfile(ModalityRouteSeeder.PROFILE_AUDIO_ID)
                .orElseThrow()
                .revision();

        enabledSeeder().seedIfNeeded();
        enabledSeeder().seedIfNeeded();

        ModelProfileDto audio =
                store.findModelProfile(ModalityRouteSeeder.PROFILE_AUDIO_ID).orElseThrow();
        assertFalse(audio.enabled());
        assertEquals(
                revisionAfterFirst, audio.revision(), "A retired profile must not churn revisions on every startup");
    }

    @Test
    void nonActiveConnectionIsActivated() {
        runLegacyMigration();
        enabledSeeder().seedIfNeeded();
        LlmConnectionDto conn =
                store.findConnection(ModalityRouteSeeder.CONN_ID).orElseThrow();
        store.saveConnection(new LlmConnectionDto(
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
                "STAGED",
                conn.revision(),
                conn.createdAt(),
                NOW));

        enabledSeeder().seedIfNeeded();

        assertExactlyOneSystemDefaultRoute();
        assertEquals(
                "ACTIVE",
                store.findConnection(ModalityRouteSeeder.CONN_ID).orElseThrow().state(),
                "A non-ACTIVE connection makes every profile on it unroutable");
    }

    @Test
    void disabledLegacyRouteStillLeavesExactlyOneSystemDefaultRoute() {
        runLegacyMigration();
        ModelRouteAssignmentDto legacy =
                store.findRouteAssignment("sys-legacy-route").orElseThrow();
        store.updateRouteAssignment(
                new ModelRouteAssignmentDto(
                        legacy.id(),
                        legacy.scopeType(),
                        legacy.scopeId(),
                        legacy.targetKind(),
                        legacy.targetId(),
                        legacy.orderedProfileIds(),
                        false,
                        legacy.revision(),
                        legacy.createdAt(),
                        NOW),
                legacy.revision());

        enabledSeeder().seedIfNeeded();

        assertExactlyOneSystemDefaultRoute();
        assertEquals(
                ModalityRouteSeeder.ROUTE_ID,
                store.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID)
                        .orElseThrow()
                        .id());
    }

    /**
     * Collision handling is non-destructive: the migrated legacy route is disabled in place and
     * still present afterwards, rather than being deleted. Losing it would make a rollback to
     * legacy routing impossible.
     */
    @Test
    void legacyRouteIsDisabledNotDeleted() {
        runLegacyMigration();

        enabledSeeder().seedIfNeeded();

        ModelRouteAssignmentDto legacy =
                store.findRouteAssignment("sys-legacy-route").orElseThrow();
        assertFalse(legacy.enabled(), "The colliding legacy route must be disabled");
        assertEquals(
                List.of("sys-legacy-profile"),
                legacy.orderedProfileIds(),
                "A disabled route must be otherwise unchanged so it can be restored");
        assertExactlyOneSystemDefaultRoute();
    }

    /**
     * The P7 exit gate: exactly one <em>enabled</em> {@code SYSTEM DEFAULT} route, it is the
     * canonical modality route, and it orders both modality profiles (text and image). Disabled
     * rows may legitimately remain — the seeder converges by disabling, never by deleting.
     */
    private void assertExactlyOneSystemDefaultRoute() {
        List<ModelRouteAssignmentDto> enabledDefaults = store.listRoutesByScope("SYSTEM", "SYSTEM").stream()
                .filter(r -> "DEFAULT".equals(r.targetKind()) && r.enabled())
                .toList();
        assertEquals(1, enabledDefaults.size(), "Exactly one enabled SYSTEM DEFAULT route must remain after seeding");
        ModelRouteAssignmentDto route = enabledDefaults.get(0);
        assertEquals(ModalityRouteSeeder.ROUTE_ID, route.id());
        assertEquals("DEFAULT", route.targetKind());
        assertTrue(route.enabled());
        assertEquals(ModalityRouteSeeder.ORDERED_PROFILE_IDS, route.orderedProfileIds());
        for (String profileId : route.orderedProfileIds()) {
            ModelProfileDto profile = store.findModelProfile(profileId).orElseThrow();
            assertTrue(profile.enabled(), "profile " + profileId + " must be active");
            assertEquals(ModalityRouteSeeder.CONN_ID, profile.connectionId());
            assertEquals(
                    "ACTIVE",
                    store.findConnection(profile.connectionId()).orElseThrow().state());
        }
    }

    // Minimal in-memory vault for test
    static class InMemoryVault implements SecretVault {
        private final Map<String, byte[]> keys = new HashMap<>();

        @Override
        public void init(String sessionId) {}

        @Override
        public void store(String keyName, byte[] secret) {
            storeSystemKey(keyName, secret);
        }

        @Override
        public void store(String keyName, byte[] secret, String userId) {
            keys.put(keyName + ":" + userId, secret.clone());
        }

        @Override
        public Optional<VaultKey> get(String keyName) {
            return retrieveSystemKey(keyName);
        }

        @Override
        public Optional<VaultKey> get(String keyName, String userId) {
            byte[] v = keys.get(keyName + ":" + userId);
            if (v == null) return Optional.empty();
            return Optional.of(new VaultKey(v.clone()));
        }

        @Override
        public void remove(String keyName, String userId) {
            keys.remove(keyName + ":" + userId);
        }

        @Override
        public void storeWorkspace(String keyName, byte[] secret, String workspaceId, String actorUserId) {}

        @Override
        public Optional<VaultKey> getWorkspace(String keyName, String workspaceId, String actorUserId) {
            return Optional.empty();
        }

        @Override
        public void removeWorkspace(String keyName, String workspaceId, String actorUserId) {}

        @Override
        public boolean systemKeyExists(String name) {
            return keys.containsKey(name);
        }

        @Override
        public Optional<VaultKey> retrieveSystemKey(String name) {
            byte[] v = keys.get(name);
            if (v == null) return Optional.empty();
            return Optional.of(new VaultKey(v.clone()));
        }

        @Override
        public void storeSystemKey(String name, byte[] secret) {
            keys.put(name, secret.clone());
        }

        @Override
        public void removeSystemKey(String name) {
            keys.remove(name);
        }
    }

    // Minimal in-memory store for test, honoring revision-checked updates and atomic activation
    static class InMemoryStore implements LlmProfileStorePort {
        private final Map<String, LlmConnectionDto> connections = new HashMap<>();
        private final Map<String, ModelProfileDto> profiles = new HashMap<>();
        private final Map<String, ModelRouteAssignmentDto> routes = new HashMap<>();
        private final Map<String, LlmConnectionSecretRefDto> secretRefs = new HashMap<>();

        @Override
        public void saveConnection(LlmConnectionDto c) {
            connections.put(c.id(), c);
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
            return List.of();
        }

        @Override
        public boolean updateConnection(LlmConnectionDto connection, int expectedRevision) {
            LlmConnectionDto current = connections.get(connection.id());
            if (current == null || current.revision() != expectedRevision) {
                return false;
            }
            connections.put(connection.id(), withRevision(connection, expectedRevision + 1, Instant.now()));
            return true;
        }

        /**
         * Mirrors the port contract: activating a connection activates its profiles and every
         * route referencing one of those profiles. Non-matching routes are left untouched.
         */
        @Override
        public boolean activateConnection(String connectionId, int expectedRevision) {
            LlmConnectionDto conn = connections.get(connectionId);
            if (conn == null || conn.revision() != expectedRevision) {
                return false;
            }
            Instant now = Instant.now();
            connections.put(
                    connectionId,
                    new LlmConnectionDto(
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
                            expectedRevision + 1,
                            conn.createdAt(),
                            now));
            List<String> profileIds = listModelProfilesByConnection(connectionId).stream()
                    .map(ModelProfileDto::id)
                    .toList();
            for (String profileId : profileIds) {
                ModelProfileDto p = profiles.get(profileId);
                profiles.put(
                        profileId,
                        new ModelProfileDto(
                                p.id(),
                                p.connectionId(),
                                p.modelId(),
                                p.structuredOutputMode(),
                                p.capabilityJson(),
                                p.safeLimitsJson(),
                                true,
                                p.revision() + 1,
                                p.createdAt(),
                                now));
            }
            for (ModelRouteAssignmentDto route : routes.values().stream().toList()) {
                if (route.orderedProfileIds().stream().noneMatch(profileIds::contains)) {
                    continue;
                }
                routes.put(
                        route.id(),
                        new ModelRouteAssignmentDto(
                                route.id(),
                                route.scopeType(),
                                route.scopeId(),
                                route.targetKind(),
                                route.targetId(),
                                route.orderedProfileIds(),
                                true,
                                route.revision() + 1,
                                route.createdAt(),
                                now));
            }
            return true;
        }

        @Override
        public int deleteConnection(String id) {
            return connections.remove(id) != null ? 1 : 0;
        }

        @Override
        public void saveSecretRef(LlmConnectionSecretRefDto ref) {
            secretRefs.put(ref.connectionId() + ":" + ref.slotId(), ref);
        }

        @Override
        public List<LlmConnectionSecretRefDto> listSecretRefs(String connectionId) {
            return secretRefs.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(connectionId + ":"))
                    .map(Map.Entry::getValue)
                    .toList();
        }

        @Override
        public int deleteSecretRefs(String connectionId) {
            long before = secretRefs.keySet().stream()
                    .filter(k -> k.startsWith(connectionId + ":"))
                    .count();
            secretRefs.keySet().removeIf(k -> k.startsWith(connectionId + ":"));
            return (int) before;
        }

        @Override
        public void saveModelProfile(ModelProfileDto p) {
            profiles.put(p.id(), p);
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
            ModelProfileDto current = profiles.get(profile.id());
            if (current == null || current.revision() != expectedRevision) {
                return false;
            }
            profiles.put(
                    profile.id(),
                    new ModelProfileDto(
                            profile.id(),
                            profile.connectionId(),
                            profile.modelId(),
                            profile.structuredOutputMode(),
                            profile.capabilityJson(),
                            profile.safeLimitsJson(),
                            profile.enabled(),
                            expectedRevision + 1,
                            profile.createdAt(),
                            profile.updatedAt()));
            return true;
        }

        @Override
        public int deleteModelProfile(String id) {
            return profiles.remove(id) != null ? 1 : 0;
        }

        @Override
        public void saveRouteAssignment(ModelRouteAssignmentDto a) {
            routes.put(a.id(), a);
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
                    .filter(r -> targetKind.equals(r.targetKind()))
                    .filter(r -> targetId == null || targetId.equals(r.targetId()))
                    .toList();
        }

        @Override
        public boolean updateRouteAssignment(ModelRouteAssignmentDto assignment, int expectedRevision) {
            ModelRouteAssignmentDto current = routes.get(assignment.id());
            if (current == null || current.revision() != expectedRevision) {
                return false;
            }
            routes.put(
                    assignment.id(),
                    new ModelRouteAssignmentDto(
                            assignment.id(),
                            assignment.scopeType(),
                            assignment.scopeId(),
                            assignment.targetKind(),
                            assignment.targetId(),
                            assignment.orderedProfileIds(),
                            assignment.enabled(),
                            expectedRevision + 1,
                            assignment.createdAt(),
                            assignment.updatedAt()));
            return true;
        }

        @Override
        public int deleteRouteAssignment(String id) {
            return routes.remove(id) != null ? 1 : 0;
        }

        private static LlmConnectionDto withRevision(LlmConnectionDto c, int revision, Instant updatedAt) {
            return new LlmConnectionDto(
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
                    c.state(),
                    revision,
                    c.createdAt(),
                    updatedAt);
        }
    }
}
