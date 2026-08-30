package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;
import com.amsha.security.api.SecretVault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ModalityRouteSeederTest {

    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

    /**
     * Capability contract of the retired audio profile. Kept here rather than in the seeder
     * because the seeder must no longer have any notion of an AUDIO chat capability.
     */
    private static final String AUDIO_CAP_JSON =
            "{\"supportedTypes\":[\"AUDIO\"],\"supportedMimeTypes\":[\"audio/mpeg\",\"audio/wav\",\"audio/ogg\"],"
                    + "\"maxFileSize\":26214400,\"maxMediaPerRequest\":1,\"supportsInlineBase64\":true,"
                    + "\"supportsMediaUrls\":false}";

    @Mock
    SecretVault vault;

    @Mock
    LlmProfileStorePort profileStore;

    ModalityRouteSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new ModalityRouteSeeder(vault, profileStore);
        seeder.seederEnabled = true;
        seeder.profileRoutingEnabled = true;
    }

    @Test
    void seedsTwoProfilesSharingOneConnection() {
        when(vault.systemKeyExists(any())).thenReturn(true);
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM")).thenReturn(List.of());
        when(profileStore.findConnection(ModalityRouteSeeder.CONN_ID)).thenReturn(Optional.empty());
        when(profileStore.findModelProfile(ModalityRouteSeeder.PROFILE_TEXT_ID)).thenReturn(Optional.empty());
        when(profileStore.findModelProfile(ModalityRouteSeeder.PROFILE_IMAGE_ID))
                .thenReturn(Optional.empty());

        seeder.seedIfNeeded();

        ArgumentCaptor<LlmConnectionDto> connCaptor = ArgumentCaptor.forClass(LlmConnectionDto.class);
        verify(profileStore).saveConnection(connCaptor.capture());
        LlmConnectionDto conn = connCaptor.getValue();
        assertEquals(ModalityRouteSeeder.CONN_ID, conn.id());
        assertEquals("omlx", conn.providerAdapterId());
        assertEquals("ACTIVE", conn.state());

        ArgumentCaptor<ModelProfileDto> profileCaptor = ArgumentCaptor.forClass(ModelProfileDto.class);
        verify(profileStore, Mockito.times(2)).saveModelProfile(profileCaptor.capture());
        List<ModelProfileDto> profiles = profileCaptor.getAllValues();
        assertEquals(2, profiles.size());
        for (ModelProfileDto p : profiles) {
            assertEquals(ModalityRouteSeeder.CONN_ID, p.connectionId());
            assertTrue(p.enabled());
        }
        assertTrue(profiles.stream().anyMatch(p -> p.modelId().equals(ModalityRouteSeeder.MODEL_TEXT)));
        assertTrue(profiles.stream().anyMatch(p -> p.modelId().equals(ModalityRouteSeeder.MODEL_IMAGE)));
        // Nemotron cannot be served by OMLX, so no AUDIO chat profile is ever installed.
        assertFalse(
                profiles.stream().anyMatch(p -> p.id().equals(ModalityRouteSeeder.PROFILE_AUDIO_ID)),
                "A permanently unloadable AUDIO profile must never be seeded");

        ArgumentCaptor<ModelRouteAssignmentDto> routeCaptor = ArgumentCaptor.forClass(ModelRouteAssignmentDto.class);
        verify(profileStore).saveRouteAssignment(routeCaptor.capture());
        ModelRouteAssignmentDto route = routeCaptor.getValue();
        assertEquals(ModalityRouteSeeder.ROUTE_ID, route.id());
        assertEquals("DEFAULT", route.targetKind());
        assertTrue(route.enabled());
        assertEquals(
                List.of(ModalityRouteSeeder.PROFILE_TEXT_ID, ModalityRouteSeeder.PROFILE_IMAGE_ID),
                route.orderedProfileIds());
    }

    @Test
    void idempotentWhenAlreadySeeded() {
        when(vault.systemKeyExists(any())).thenReturn(true);
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM")).thenReturn(List.of(canonicalRoute()));
        when(profileStore.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID)).thenReturn(Optional.of(canonicalRoute()));
        when(profileStore.findConnection(ModalityRouteSeeder.CONN_ID)).thenReturn(Optional.of(activeConnection()));
        when(profileStore.listSecretRefs(ModalityRouteSeeder.CONN_ID)).thenReturn(List.of());
        seedProfile(ModalityRouteSeeder.PROFILE_TEXT_ID, ModalityRouteSeeder.MODEL_TEXT, ModalityRouteSeeder.CAP_TEXT);
        seedProfile(
                ModalityRouteSeeder.PROFILE_IMAGE_ID, ModalityRouteSeeder.MODEL_IMAGE, ModalityRouteSeeder.CAP_IMAGE);

        seeder.seedIfNeeded();

        verify(profileStore, never()).saveConnection(any());
        verify(profileStore, never()).saveModelProfile(any());
        verify(profileStore, never()).saveRouteAssignment(any());
        verify(profileStore, never()).updateRouteAssignment(any(), anyInt());
        verify(profileStore, never()).deleteRouteAssignment(any());
    }

    @Test
    void skipsWhenVaultKeyMissing() {
        when(vault.systemKeyExists(any())).thenReturn(false);

        seeder.seedIfNeeded();

        verify(profileStore, never()).saveConnection(any());
        verify(profileStore, never()).saveModelProfile(any());
        verify(profileStore, never()).saveRouteAssignment(any());
        // The guard must run before any mutation: an existing route is never disabled or destroyed
        // when the seeder cannot install its replacement.
        verify(profileStore, never()).updateRouteAssignment(any(), anyInt());
        verify(profileStore, never()).deleteRouteAssignment(any());
    }

    @Test
    void skipsWhenSeederDisabled() {
        seeder.seederEnabled = false;
        seeder.seedIfNeeded();

        verify(profileStore, never()).saveConnection(any());
        verify(profileStore, never()).saveModelProfile(any());
        verify(profileStore, never()).saveRouteAssignment(any());
        verify(profileStore, never()).updateRouteAssignment(any(), anyInt());
        verify(profileStore, never()).deleteRouteAssignment(any());
    }

    @Test
    void skipsWhenProfileRoutingDisabled() {
        seeder.profileRoutingEnabled = false;
        seeder.seedIfNeeded();

        verify(profileStore, never()).saveConnection(any());
        verify(profileStore, never()).saveModelProfile(any());
        verify(profileStore, never()).saveRouteAssignment(any());
        verify(profileStore, never()).updateRouteAssignment(any(), anyInt());
        verify(profileStore, never()).deleteRouteAssignment(any());
    }

    /**
     * The ordering invariant that the previous revision violated: the legacy route must be
     * neutralised only <em>after</em> the replacement is installed, and it must be disabled rather
     * than deleted. Deleting first meant a failure in between left zero SYSTEM DEFAULT routes.
     */
    @Test
    void disablesLegacySystemRouteOnlyAfterTheReplacementIsInstalled() {
        ModelRouteAssignmentDto legacyRoute = new ModelRouteAssignmentDto(
                "sys-legacy-route",
                "SYSTEM",
                "SYSTEM",
                "DEFAULT",
                null,
                List.of("sys-legacy-profile"),
                true,
                1,
                NOW,
                NOW);
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM")).thenReturn(List.of(legacyRoute));
        when(profileStore.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID)).thenReturn(Optional.empty());
        when(vault.systemKeyExists(any())).thenReturn(true);
        when(profileStore.findConnection(ModalityRouteSeeder.CONN_ID)).thenReturn(Optional.empty());
        when(profileStore.findModelProfile(ModalityRouteSeeder.PROFILE_TEXT_ID)).thenReturn(Optional.empty());
        when(profileStore.findModelProfile(ModalityRouteSeeder.PROFILE_IMAGE_ID))
                .thenReturn(Optional.empty());
        when(profileStore.findModelProfile(ModalityRouteSeeder.PROFILE_AUDIO_ID))
                .thenReturn(Optional.empty());

        seeder.seedIfNeeded();

        InOrder inOrder = inOrder(profileStore);
        inOrder.verify(profileStore).saveRouteAssignment(any());
        inOrder.verify(profileStore).updateRouteAssignment(any(), anyInt());

        ArgumentCaptor<ModelRouteAssignmentDto> captor = ArgumentCaptor.forClass(ModelRouteAssignmentDto.class);
        verify(profileStore).updateRouteAssignment(captor.capture(), anyInt());
        ModelRouteAssignmentDto disabled = captor.getValue();
        assertEquals("sys-legacy-route", disabled.id());
        assertFalse(disabled.enabled(), "Colliding routes are disabled, never deleted");
        // Retained verbatim apart from the flag, so an operator can restore legacy routing by hand.
        assertEquals(List.of("sys-legacy-profile"), disabled.orderedProfileIds());
        assertEquals("DEFAULT", disabled.targetKind());
        verify(profileStore, never()).deleteRouteAssignment(any());
    }

    @Test
    void leavesAlreadyDisabledLegacyRouteAlone() {
        ModelRouteAssignmentDto disabledLegacy = new ModelRouteAssignmentDto(
                "sys-legacy-route",
                "SYSTEM",
                "SYSTEM",
                "DEFAULT",
                null,
                List.of("sys-legacy-profile"),
                false,
                3,
                NOW,
                NOW);
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM")).thenReturn(List.of(disabledLegacy));
        when(profileStore.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID)).thenReturn(Optional.empty());
        when(vault.systemKeyExists(any())).thenReturn(true);
        when(profileStore.findConnection(ModalityRouteSeeder.CONN_ID)).thenReturn(Optional.of(activeConnection()));
        when(profileStore.listSecretRefs(ModalityRouteSeeder.CONN_ID)).thenReturn(List.of());
        seedProfile(ModalityRouteSeeder.PROFILE_TEXT_ID, ModalityRouteSeeder.MODEL_TEXT, ModalityRouteSeeder.CAP_TEXT);
        seedProfile(
                ModalityRouteSeeder.PROFILE_IMAGE_ID, ModalityRouteSeeder.MODEL_IMAGE, ModalityRouteSeeder.CAP_IMAGE);

        seeder.seedIfNeeded();

        verify(profileStore).saveRouteAssignment(any());
        verify(profileStore, never()).updateRouteAssignment(any(), anyInt());
        verify(profileStore, never()).deleteRouteAssignment(any());
    }

    /**
     * The failure mode the old delete-first ordering made catastrophic: if the canonical route
     * cannot be installed, nothing that is currently serving traffic may be touched.
     */
    @Test
    void leavesLegacyRouteUntouchedWhenTheReplacementCannotBeInstalled() {
        ModelRouteAssignmentDto staleCanonical = new ModelRouteAssignmentDto(
                ModalityRouteSeeder.ROUTE_ID,
                "SYSTEM",
                "SYSTEM",
                "DEFAULT",
                null,
                List.of(ModalityRouteSeeder.PROFILE_TEXT_ID),
                true,
                4,
                NOW,
                NOW);
        ModelRouteAssignmentDto enabledLegacy = new ModelRouteAssignmentDto(
                "sys-legacy-route",
                "SYSTEM",
                "SYSTEM",
                "DEFAULT",
                null,
                List.of("sys-legacy-profile"),
                true,
                1,
                NOW,
                NOW);
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM")).thenReturn(List.of(staleCanonical, enabledLegacy));
        when(profileStore.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID)).thenReturn(Optional.of(staleCanonical));
        when(vault.systemKeyExists(any())).thenReturn(true);
        when(profileStore.findConnection(ModalityRouteSeeder.CONN_ID)).thenReturn(Optional.of(activeConnection()));
        when(profileStore.listSecretRefs(ModalityRouteSeeder.CONN_ID)).thenReturn(List.of());
        seedProfile(ModalityRouteSeeder.PROFILE_TEXT_ID, ModalityRouteSeeder.MODEL_TEXT, ModalityRouteSeeder.CAP_TEXT);
        seedProfile(
                ModalityRouteSeeder.PROFILE_IMAGE_ID, ModalityRouteSeeder.MODEL_IMAGE, ModalityRouteSeeder.CAP_IMAGE);
        when(profileStore.updateRouteAssignment(any(), anyInt())).thenReturn(false);

        seeder.seedIfNeeded();

        // Two attempts at the canonical route (initial + one fresh-revision retry), and nothing else.
        ArgumentCaptor<ModelRouteAssignmentDto> captor = ArgumentCaptor.forClass(ModelRouteAssignmentDto.class);
        verify(profileStore, times(2)).updateRouteAssignment(captor.capture(), anyInt());
        for (ModelRouteAssignmentDto written : captor.getAllValues()) {
            assertEquals(
                    ModalityRouteSeeder.ROUTE_ID,
                    written.id(),
                    "Only the canonical route may be written before it is confirmed installed");
            assertTrue(written.enabled());
        }
        // The legacy route keeps serving: never disabled, never deleted, and no profile retired.
        verify(profileStore, never()).deleteRouteAssignment(any());
        verify(profileStore, never()).updateModelProfile(any(), anyInt());
    }

    @Test
    void reactivatesPersistedRouteThatIsDisabled() {
        ModelRouteAssignmentDto persisted = new ModelRouteAssignmentDto(
                ModalityRouteSeeder.ROUTE_ID,
                "SYSTEM",
                "SYSTEM",
                "DEFAULT",
                null,
                ModalityRouteSeeder.ORDERED_PROFILE_IDS,
                false,
                7,
                NOW,
                NOW);
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM")).thenReturn(List.of(persisted));
        when(profileStore.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID)).thenReturn(Optional.of(persisted));
        when(vault.systemKeyExists(any())).thenReturn(true);
        when(profileStore.findConnection(ModalityRouteSeeder.CONN_ID)).thenReturn(Optional.of(activeConnection()));
        when(profileStore.listSecretRefs(ModalityRouteSeeder.CONN_ID)).thenReturn(List.of());
        seedProfile(ModalityRouteSeeder.PROFILE_TEXT_ID, ModalityRouteSeeder.MODEL_TEXT, ModalityRouteSeeder.CAP_TEXT);
        seedProfile(
                ModalityRouteSeeder.PROFILE_IMAGE_ID, ModalityRouteSeeder.MODEL_IMAGE, ModalityRouteSeeder.CAP_IMAGE);
        when(profileStore.updateRouteAssignment(any(), anyInt())).thenReturn(true);

        seeder.seedIfNeeded();

        // Activation repairs the persisted row; it never re-inserts a duplicate.
        verify(profileStore, never()).saveRouteAssignment(any());
        ArgumentCaptor<ModelRouteAssignmentDto> captor = ArgumentCaptor.forClass(ModelRouteAssignmentDto.class);
        verify(profileStore).updateRouteAssignment(captor.capture(), anyInt());
        ModelRouteAssignmentDto repaired = captor.getValue();
        assertTrue(repaired.enabled(), "A persisted-but-disabled route must be re-enabled");
        assertEquals(ModalityRouteSeeder.ORDERED_PROFILE_IDS, repaired.orderedProfileIds());
        assertEquals("DEFAULT", repaired.targetKind());
    }

    @Test
    void repairsStaleOrderedProfileIds() {
        ModelRouteAssignmentDto stale = new ModelRouteAssignmentDto(
                ModalityRouteSeeder.ROUTE_ID,
                "SYSTEM",
                "SYSTEM",
                "DEFAULT",
                null,
                List.of(ModalityRouteSeeder.PROFILE_TEXT_ID),
                true,
                4,
                NOW,
                NOW);
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM")).thenReturn(List.of(stale));
        when(profileStore.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID)).thenReturn(Optional.of(stale));
        when(vault.systemKeyExists(any())).thenReturn(true);
        when(profileStore.findConnection(ModalityRouteSeeder.CONN_ID)).thenReturn(Optional.of(activeConnection()));
        when(profileStore.listSecretRefs(ModalityRouteSeeder.CONN_ID)).thenReturn(List.of());
        seedProfile(ModalityRouteSeeder.PROFILE_TEXT_ID, ModalityRouteSeeder.MODEL_TEXT, ModalityRouteSeeder.CAP_TEXT);
        seedProfile(
                ModalityRouteSeeder.PROFILE_IMAGE_ID, ModalityRouteSeeder.MODEL_IMAGE, ModalityRouteSeeder.CAP_IMAGE);
        when(profileStore.updateRouteAssignment(any(), anyInt())).thenReturn(true);

        seeder.seedIfNeeded();

        ArgumentCaptor<ModelRouteAssignmentDto> captor = ArgumentCaptor.forClass(ModelRouteAssignmentDto.class);
        verify(profileStore).updateRouteAssignment(captor.capture(), anyInt());
        assertEquals(ModalityRouteSeeder.ORDERED_PROFILE_IDS, captor.getValue().orderedProfileIds());
    }

    @Test
    void retriesOnceWhenRevisionConflictThenSucceeds() {
        ModelRouteAssignmentDto disabled = new ModelRouteAssignmentDto(
                ModalityRouteSeeder.ROUTE_ID,
                "SYSTEM",
                "SYSTEM",
                "DEFAULT",
                null,
                ModalityRouteSeeder.ORDERED_PROFILE_IDS,
                false,
                2,
                NOW,
                NOW);
        ModelRouteAssignmentDto concurrent = new ModelRouteAssignmentDto(
                ModalityRouteSeeder.ROUTE_ID,
                "SYSTEM",
                "SYSTEM",
                "DEFAULT",
                null,
                ModalityRouteSeeder.ORDERED_PROFILE_IDS,
                false,
                9,
                NOW,
                NOW);
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM")).thenReturn(List.of(disabled));
        when(profileStore.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID))
                .thenReturn(Optional.of(disabled))
                .thenReturn(Optional.of(concurrent));
        when(vault.systemKeyExists(any())).thenReturn(true);
        when(profileStore.findConnection(ModalityRouteSeeder.CONN_ID)).thenReturn(Optional.of(activeConnection()));
        when(profileStore.listSecretRefs(ModalityRouteSeeder.CONN_ID)).thenReturn(List.of());
        seedProfile(ModalityRouteSeeder.PROFILE_TEXT_ID, ModalityRouteSeeder.MODEL_TEXT, ModalityRouteSeeder.CAP_TEXT);
        seedProfile(
                ModalityRouteSeeder.PROFILE_IMAGE_ID, ModalityRouteSeeder.MODEL_IMAGE, ModalityRouteSeeder.CAP_IMAGE);
        when(profileStore.updateRouteAssignment(any(), anyInt()))
                .thenReturn(false)
                .thenReturn(true);

        seeder.seedIfNeeded();

        verify(profileStore, times(2)).updateRouteAssignment(any(), anyInt());
    }

    @Test
    void activatesConnectionThatIsNotActive() {
        LlmConnectionDto staged = new LlmConnectionDto(
                ModalityRouteSeeder.CONN_ID,
                "SYSTEM",
                "SYSTEM",
                "system",
                "Shared OMLX modality connection (text/image/audio)",
                "omlx",
                "default",
                null,
                "API_KEY",
                null,
                "STAGED",
                5,
                NOW,
                NOW);
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM")).thenReturn(List.of(canonicalRoute()));
        when(profileStore.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID)).thenReturn(Optional.of(canonicalRoute()));
        when(vault.systemKeyExists(any())).thenReturn(true);
        when(profileStore.findConnection(ModalityRouteSeeder.CONN_ID)).thenReturn(Optional.of(staged));
        when(profileStore.listSecretRefs(ModalityRouteSeeder.CONN_ID)).thenReturn(List.of());
        when(profileStore.activateConnection(ModalityRouteSeeder.CONN_ID, 5)).thenReturn(true);
        seedProfile(ModalityRouteSeeder.PROFILE_TEXT_ID, ModalityRouteSeeder.MODEL_TEXT, ModalityRouteSeeder.CAP_TEXT);
        seedProfile(
                ModalityRouteSeeder.PROFILE_IMAGE_ID, ModalityRouteSeeder.MODEL_IMAGE, ModalityRouteSeeder.CAP_IMAGE);

        seeder.seedIfNeeded();

        verify(profileStore).activateConnection(ModalityRouteSeeder.CONN_ID, 5);
        verify(profileStore, never()).saveConnection(any());
    }

    @Test
    void repairsDisabledProfile() {
        ModelProfileDto disabled = new ModelProfileDto(
                ModalityRouteSeeder.PROFILE_IMAGE_ID,
                ModalityRouteSeeder.CONN_ID,
                ModalityRouteSeeder.MODEL_IMAGE,
                "PROMPT_DIRECTIVE",
                ModalityRouteSeeder.CAP_IMAGE,
                null,
                false,
                3,
                NOW,
                NOW);
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM")).thenReturn(List.of(canonicalRoute()));
        when(profileStore.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID)).thenReturn(Optional.of(canonicalRoute()));
        when(vault.systemKeyExists(any())).thenReturn(true);
        when(profileStore.findConnection(ModalityRouteSeeder.CONN_ID)).thenReturn(Optional.of(activeConnection()));
        when(profileStore.listSecretRefs(ModalityRouteSeeder.CONN_ID)).thenReturn(List.of());
        seedProfile(ModalityRouteSeeder.PROFILE_TEXT_ID, ModalityRouteSeeder.MODEL_TEXT, ModalityRouteSeeder.CAP_TEXT);
        when(profileStore.findModelProfile(ModalityRouteSeeder.PROFILE_IMAGE_ID))
                .thenReturn(Optional.of(disabled));
        when(profileStore.updateModelProfile(any(), anyInt())).thenReturn(true);

        seeder.seedIfNeeded();

        ArgumentCaptor<ModelProfileDto> captor = ArgumentCaptor.forClass(ModelProfileDto.class);
        verify(profileStore).updateModelProfile(captor.capture(), anyInt());
        assertTrue(captor.getValue().enabled());
        assertEquals(ModalityRouteSeeder.PROFILE_IMAGE_ID, captor.getValue().id());
    }

    /**
     * An earlier revision of the seeder installed {@code modality-audio-nemotron}. It must not
     * survive as a selectable profile on a machine that already has it persisted, because OMLX
     * cannot load that architecture.
     */
    @Test
    void disablesDeprecatedAudioProfileLeftByAnEarlierRun() {
        ModelProfileDto staleAudio = new ModelProfileDto(
                ModalityRouteSeeder.PROFILE_AUDIO_ID,
                ModalityRouteSeeder.CONN_ID,
                ModalityRouteSeeder.MODEL_AUDIO,
                "PROMPT_DIRECTIVE",
                AUDIO_CAP_JSON,
                null,
                true,
                3,
                NOW,
                NOW);
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM")).thenReturn(List.of(canonicalRoute()));
        when(profileStore.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID)).thenReturn(Optional.of(canonicalRoute()));
        when(vault.systemKeyExists(any())).thenReturn(true);
        when(profileStore.findConnection(ModalityRouteSeeder.CONN_ID)).thenReturn(Optional.of(activeConnection()));
        when(profileStore.listSecretRefs(ModalityRouteSeeder.CONN_ID)).thenReturn(List.of());
        seedProfile(ModalityRouteSeeder.PROFILE_TEXT_ID, ModalityRouteSeeder.MODEL_TEXT, ModalityRouteSeeder.CAP_TEXT);
        seedProfile(
                ModalityRouteSeeder.PROFILE_IMAGE_ID, ModalityRouteSeeder.MODEL_IMAGE, ModalityRouteSeeder.CAP_IMAGE);
        when(profileStore.findModelProfile(ModalityRouteSeeder.PROFILE_AUDIO_ID))
                .thenReturn(Optional.of(staleAudio));
        when(profileStore.updateModelProfile(any(), anyInt())).thenReturn(true);

        seeder.seedIfNeeded();

        ArgumentCaptor<ModelProfileDto> captor = ArgumentCaptor.forClass(ModelProfileDto.class);
        verify(profileStore).updateModelProfile(captor.capture(), anyInt());
        ModelProfileDto retired = captor.getValue();
        assertEquals(ModalityRouteSeeder.PROFILE_AUDIO_ID, retired.id());
        assertFalse(retired.enabled(), "A persisted-but-unloadable audio profile must be disabled, not deleted");
        // Retirement only flips the flag; it must not silently re-point the model or the contract.
        assertEquals(ModalityRouteSeeder.MODEL_AUDIO, retired.modelId());
        assertEquals(AUDIO_CAP_JSON, retired.capabilityJson());
        verify(profileStore, never()).deleteModelProfile(any());
    }

    @Test
    void retirementIsIdempotentWhenAudioProfileAlreadyDisabled() {
        ModelProfileDto retiredAudio = new ModelProfileDto(
                ModalityRouteSeeder.PROFILE_AUDIO_ID,
                ModalityRouteSeeder.CONN_ID,
                ModalityRouteSeeder.MODEL_AUDIO,
                "PROMPT_DIRECTIVE",
                AUDIO_CAP_JSON,
                null,
                false,
                4,
                NOW,
                NOW);
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM")).thenReturn(List.of(canonicalRoute()));
        when(profileStore.findRouteAssignment(ModalityRouteSeeder.ROUTE_ID)).thenReturn(Optional.of(canonicalRoute()));
        when(vault.systemKeyExists(any())).thenReturn(true);
        when(profileStore.findConnection(ModalityRouteSeeder.CONN_ID)).thenReturn(Optional.of(activeConnection()));
        when(profileStore.listSecretRefs(ModalityRouteSeeder.CONN_ID)).thenReturn(List.of());
        seedProfile(ModalityRouteSeeder.PROFILE_TEXT_ID, ModalityRouteSeeder.MODEL_TEXT, ModalityRouteSeeder.CAP_TEXT);
        seedProfile(
                ModalityRouteSeeder.PROFILE_IMAGE_ID, ModalityRouteSeeder.MODEL_IMAGE, ModalityRouteSeeder.CAP_IMAGE);
        when(profileStore.findModelProfile(ModalityRouteSeeder.PROFILE_AUDIO_ID))
                .thenReturn(Optional.of(retiredAudio));

        seeder.seedIfNeeded();

        verify(profileStore, never()).updateModelProfile(any(), anyInt());
    }

    /**
     * Guard against re-introducing a profile the runtime cannot serve: no retired profile may
     * ever appear in the active route order.
     */
    @Test
    void retiredProfilesAreNeverRoutable() {
        assertTrue(
                ModalityRouteSeeder.ORDERED_PROFILE_IDS.stream()
                        .noneMatch(ModalityRouteSeeder.DEPRECATED_PROFILE_IDS::contains),
                "Retired profiles must never appear in the active route order");
        assertFalse(
                ModalityRouteSeeder.ORDERED_PROFILE_IDS.contains(ModalityRouteSeeder.PROFILE_AUDIO_ID),
                "Native audio-chat is BLOCKED on OMLX; audio must not be routed to a chat profile");
    }

    @Test
    void capabilityJsonPreservesTypedModalities() {
        assertTrue(ModalityRouteSeeder.CAP_TEXT.contains("\"supportedTypes\":[]"));
        assertTrue(ModalityRouteSeeder.CAP_IMAGE.contains("\"IMAGE\""));
        assertTrue(ModalityRouteSeeder.CAP_IMAGE.contains("image/png"));
        assertTrue(ModalityRouteSeeder.CAP_IMAGE.contains("image/gif"));
        assertFalse(ModalityRouteSeeder.CAP_TEXT.contains("IMAGE"));
    }

    private void seedProfile(String profileId, String modelId, String capabilityJson) {
        when(profileStore.findModelProfile(profileId))
                .thenReturn(Optional.of(new ModelProfileDto(
                        profileId,
                        ModalityRouteSeeder.CONN_ID,
                        modelId,
                        "PROMPT_DIRECTIVE",
                        capabilityJson,
                        null,
                        true,
                        1,
                        NOW,
                        NOW)));
    }

    private static LlmConnectionDto activeConnection() {
        return new LlmConnectionDto(
                ModalityRouteSeeder.CONN_ID,
                "SYSTEM",
                "SYSTEM",
                "system",
                "Shared OMLX modality connection (text/image/audio)",
                "omlx",
                "default",
                null,
                "API_KEY",
                null,
                "ACTIVE",
                1,
                NOW,
                NOW);
    }

    private static ModelRouteAssignmentDto canonicalRoute() {
        return new ModelRouteAssignmentDto(
                ModalityRouteSeeder.ROUTE_ID,
                "SYSTEM",
                "SYSTEM",
                "DEFAULT",
                null,
                ModalityRouteSeeder.ORDERED_PROFILE_IDS,
                true,
                1,
                NOW,
                NOW);
    }
}
