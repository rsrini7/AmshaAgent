package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OnboardingProfileExporterTest {

    @Mock
    LlmProfileStorePort profileStore;

    @InjectMocks
    OnboardingProfileExporter exporter;

    @Test
    void testExportProfiles() {
        Instant now = Instant.now();
        LlmConnectionDto conn = new LlmConnectionDto(
                "c1",
                "USER",
                "u1",
                "u1",
                "Conn1",
                "openai",
                "ep-custom",
                "https://api.custom.com",
                "API_KEY",
                "{\"requireProxy\":true}",
                "ACTIVE",
                1,
                now,
                now);
        ModelProfileDto prof = new ModelProfileDto("p1", "c1", "gpt-4o", "NATIVE", null, null, true, 1, now, now);
        ModelRouteAssignmentDto route =
                new ModelRouteAssignmentDto("r1", "USER", "u1", "SYSTEM", "", List.of("p1"), true, 1, now, now);

        when(profileStore.listConnectionsByCreatedBy("u1")).thenReturn(List.of(conn));
        when(profileStore.listModelProfilesByConnection("c1")).thenReturn(List.of(prof));
        when(profileStore.listRoutesByScope("USER", "u1")).thenReturn(List.of(route));

        Map<String, Object> result = exporter.exportProfiles("u1", profileStore);

        assertNotNull(result);
        assertEquals(1, result.get("version"));
        assertTrue(result.containsKey("connections"));
        assertTrue(result.containsKey("profiles"));
        assertTrue(result.containsKey("routes"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conns = (List<Map<String, Object>>) result.get("connections");
        assertEquals(1, conns.size());
        assertEquals("ep-custom", conns.get(0).get("endpointProfileId"));
        assertEquals("https://api.custom.com", conns.get(0).get("customBaseUrl"));
        assertFalse(conns.get(0).containsKey("authScheme"), "export must not include auth scheme details");
        assertEquals("{\"requireProxy\":true}", conns.get(0).get("networkProfileJson"));
    }

    @Test
    void testImportProfiles() {
        Map<String, Object> data = Map.of(
                "connections",
                        List.of(Map.of(
                                "id",
                                "c1",
                                "scopeType",
                                "USER",
                                "scopeId",
                                "u1",
                                "customBaseUrl",
                                "http://localhost:11434",
                                "networkProfileJson",
                                "{\"requireProxy\":false}")),
                "profiles", List.of(Map.of("id", "p1", "connectionId", "c1", "modelId", "gpt-4o")),
                "routes",
                        List.of(Map.of(
                                "id",
                                "r1",
                                "scopeType",
                                "USER",
                                "scopeId",
                                "u1",
                                "targetKind",
                                "DEFAULT",
                                "orderedProfileIds",
                                List.of("p1"))));

        exporter.importProfiles("u1", data, profileStore);

        ArgumentCaptor<LlmConnectionDto> connCaptor = ArgumentCaptor.forClass(LlmConnectionDto.class);
        verify(profileStore).saveConnection(connCaptor.capture());
        assertEquals("http://localhost:11434", connCaptor.getValue().customBaseUrl());
        assertEquals("{\"requireProxy\":false}", connCaptor.getValue().networkProfileJson());

        verify(profileStore).saveModelProfile(any());
        verify(profileStore).saveRouteAssignment(any());
    }

    @Test
    void testImportProfiles_nullOrEmptyData_safelyIgnored() {
        assertDoesNotThrow(() -> exporter.importProfiles("u1", null, profileStore));
        assertDoesNotThrow(() -> exporter.importProfiles("u1", Map.of(), profileStore));
        assertDoesNotThrow(() -> exporter.importProfiles("u1", Map.of("connections", List.of()), null));
        verifyNoInteractions(profileStore);
    }

    @Test
    void testImportProfiles_corruptOrMissingIds_skipsInvalidRecords() {
        Map<String, Object> corruptData = Map.of(
                "connections",
                List.of(Map.of("displayName", "invalid-no-id"), Map.of("id", "   ")),
                "profiles",
                List.of(Map.of("modelId", "gpt-4o"), Map.of("id", "p1")),
                "routes",
                List.of(Map.of("id", "r1", "scopeType", "USER"), Map.of("id", "r2", "scopeId", "u1")));

        exporter.importProfiles("u1", corruptData, profileStore);

        verify(profileStore, never()).saveConnection(any());
        verify(profileStore, never()).saveModelProfile(any());
        verify(profileStore, never()).saveRouteAssignment(any());
    }

    @Test
    void testExportProfiles_emptyStore_returnsEmptyMaps() {
        when(profileStore.listConnectionsByCreatedBy("u2")).thenReturn(List.of());

        Map<String, Object> result = exporter.exportProfiles("u2", profileStore);

        assertNotNull(result);
        assertEquals(List.of(), result.get("connections"));
        assertEquals(List.of(), result.get("profiles"));
        assertEquals(List.of(), result.get("routes"));
    }

    @Test
    void testImportProfiles_rejectsNonUserDataScope() {
        Map<String, Object> data =
                Map.of("connections", List.of(Map.of("id", "c1", "scopeType", "SYSTEM", "scopeId", "u1")));
        assertThrows(IllegalArgumentException.class, () -> exporter.importProfiles("u1", data, profileStore));
    }

    @Test
    void testImportProfiles_rejectsForeignUserScope() {
        Map<String, Object> data =
                Map.of("connections", List.of(Map.of("id", "c1", "scopeType", "USER", "scopeId", "attacker")));
        assertThrows(IllegalArgumentException.class, () -> exporter.importProfiles("u1", data, profileStore));
    }

    @Test
    void testImportProfiles_routesImportedAsDisabled() {
        Map<String, Object> data = Map.of(
                "connections", List.of(Map.of("id", "c1")),
                "routes", List.of(Map.of("id", "r1", "targetKind", "SYSTEM", "enabled", true)));

        exporter.importProfiles("u1", data, profileStore);

        ArgumentCaptor<ModelRouteAssignmentDto> captor = ArgumentCaptor.forClass(ModelRouteAssignmentDto.class);
        verify(profileStore).saveRouteAssignment(captor.capture());
        assertFalse(captor.getValue().enabled(), "Imported routes must be disabled until committed");
        assertEquals("USER", captor.getValue().scopeType());
        assertEquals("u1", captor.getValue().scopeId());
    }

    @Test
    void testImportProfiles_rejectsUnknownOrderedProfileId() {
        Map<String, Object> data = Map.of(
                "connections", List.of(Map.of("id", "c1")),
                "profiles", List.of(Map.of("id", "p1", "connectionId", "c1")),
                "routes",
                        List.of(Map.of(
                                "id",
                                "r1",
                                "targetKind",
                                "SYSTEM",
                                "orderedProfileIds",
                                List.of("unknown-foreign-profile"))));

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> exporter.importProfiles("u1", data, profileStore));
        assertTrue(ex.getMessage().contains("unknown profileId"));
    }

    @Test
    void testImportProfiles_rejectsCrossScopeConnectionIdWhenConnectionsOmitted() {
        // connections block omitted entirely; profile references foreign/unknown connectionId
        Map<String, Object> data =
                Map.of("profiles", List.of(Map.of("id", "p1", "connectionId", "foreign-conn-id", "modelId", "gpt-4o")));

        when(profileStore.listConnectionsByCreatedBy("u1")).thenReturn(List.of());

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> exporter.importProfiles("u1", data, profileStore));
        assertTrue(ex.getMessage().contains("unknown connectionId"));
    }
}
