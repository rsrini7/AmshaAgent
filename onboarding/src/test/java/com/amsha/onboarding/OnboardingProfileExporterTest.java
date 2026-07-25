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
                "c1", "USER", "u1", "u1", "Conn1", "openai", null, null, "API_KEY", null, "ACTIVE", 1, now, now);
        ModelProfileDto prof = new ModelProfileDto("p1", "c1", "gpt-4o", "NATIVE", null, null, true, 1, now, now);
        ModelRouteAssignmentDto route =
                new ModelRouteAssignmentDto("r1", "USER", "u1", "SYSTEM", "", List.of("p1"), true, 1, now, now);

        when(profileStore.listConnectionsByCreatedBy("u1")).thenReturn(List.of(conn));
        when(profileStore.listModelProfilesByConnection("c1")).thenReturn(List.of(prof));
        when(profileStore.listRoutesByScope("USER", "u1")).thenReturn(List.of(route));

        Map<String, Object> result = exporter.exportProfiles("u1", profileStore);

        assertNotNull(result);
        assertTrue(result.containsKey("connections"));
        assertTrue(result.containsKey("profiles"));
        assertTrue(result.containsKey("routes"));
    }

    @Test
    void testImportProfiles() {
        Map<String, Object> data = Map.of(
                "connections", List.of(Map.of("id", "c1", "scopeType", "USER", "scopeId", "u1")),
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
                                "SYSTEM",
                                "orderedProfileIds",
                                List.of("p1"))));

        exporter.importProfiles("u1", data, profileStore);

        verify(profileStore).saveConnection(any());
        verify(profileStore).saveModelProfile(any());
        verify(profileStore).saveRouteAssignment(any());
    }
}
