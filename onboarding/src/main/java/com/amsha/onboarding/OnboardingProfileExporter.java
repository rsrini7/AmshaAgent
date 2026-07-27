package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.core.routing.ModelDefaults;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;

/**
 * Handles exporting and importing non-secret profiles.
 */
@ApplicationScoped
public class OnboardingProfileExporter {

    public Map<String, Object> exportProfiles(String ownerId, LlmProfileStorePort profileStore) {
        List<LlmConnectionDto> connections = profileStore.listConnectionsByCreatedBy(ownerId);
        List<Map<String, Object>> connMaps = new ArrayList<>();
        for (LlmConnectionDto c : connections) {
            connMaps.add(Map.of(
                    "id",
                    c.id(),
                    "scopeType",
                    c.scopeType(),
                    "scopeId",
                    c.scopeId(),
                    "displayName",
                    c.displayName() == null ? "" : c.displayName(),
                    "providerAdapterId",
                    c.providerAdapterId(),
                    "state",
                    c.state()));
        }
        List<Map<String, Object>> profileMaps = new ArrayList<>();
        List<Map<String, Object>> routeMaps = new ArrayList<>();
        Set<String> scopesSeen = new HashSet<>();
        for (LlmConnectionDto c : connections) {
            for (ModelProfileDto p : profileStore.listModelProfilesByConnection(c.id())) {
                profileMaps.add(Map.of(
                        "id",
                        p.id(),
                        "connectionId",
                        p.connectionId(),
                        "modelId",
                        p.modelId(),
                        "structuredOutputMode",
                        p.structuredOutputMode() == null ? "" : p.structuredOutputMode(),
                        "enabled",
                        p.enabled()));
            }
            String scopeKey = c.scopeType() + ":" + c.scopeId();
            if (scopesSeen.add(scopeKey)) {
                for (ModelRouteAssignmentDto r : profileStore.listRoutesByScope(c.scopeType(), c.scopeId())) {
                    routeMaps.add(Map.of(
                            "id",
                            r.id(),
                            "scopeType",
                            r.scopeType(),
                            "scopeId",
                            r.scopeId(),
                            "targetKind",
                            r.targetKind(),
                            "targetId",
                            r.targetId() == null ? "" : r.targetId(),
                            "orderedProfileIds",
                            r.orderedProfileIds(),
                            "enabled",
                            r.enabled()));
                }
            }
        }
        return Map.of("connections", connMaps, "profiles", profileMaps, "routes", routeMaps);
    }

    @SuppressWarnings("unchecked")
    public void importProfiles(String ownerId, Map<String, Object> data, LlmProfileStorePort profileStore) {
        List<Map<String, Object>> connections = (List<Map<String, Object>>) data.getOrDefault("connections", List.of());
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) data.getOrDefault("profiles", List.of());
        Instant now = Instant.now();
        for (Map<String, Object> c : connections) {
            String connId = (String) c.get("id");
            if (connId == null || connId.isBlank()) continue;
            profileStore.saveConnection(new LlmConnectionDto(
                    connId,
                    (String) c.getOrDefault("scopeType", "USER"),
                    (String) c.getOrDefault("scopeId", ownerId),
                    ownerId,
                    (String) c.getOrDefault("displayName", connId),
                    (String) c.getOrDefault("providerAdapterId", "openai"),
                    null,
                    null,
                    "API_KEY",
                    null,
                    "STAGED",
                    1,
                    now,
                    now));
        }
        for (Map<String, Object> p : profiles) {
            String profileId = (String) p.get("id");
            String connId = (String) p.get("connectionId");
            if (profileId == null || connId == null) continue;
            profileStore.saveModelProfile(new ModelProfileDto(
                    profileId,
                    connId,
                    (String) p.getOrDefault("modelId", ModelDefaults.DEFAULT_OPENAI_MODEL),
                    (String) p.getOrDefault("structuredOutputMode", "NATIVE"),
                    null,
                    null,
                    false,
                    1,
                    now,
                    now));
        }
        List<Map<String, Object>> routes = (List<Map<String, Object>>) data.getOrDefault("routes", List.of());
        for (Map<String, Object> r : routes) {
            String routeId = (String) r.get("id");
            String scopeType = (String) r.get("scopeType");
            String scopeId = (String) r.get("scopeId");
            String targetKind = (String) r.get("targetKind");
            if (routeId == null || scopeType == null || scopeId == null || targetKind == null) continue;
            profileStore.saveRouteAssignment(new ModelRouteAssignmentDto(
                    routeId,
                    scopeType,
                    scopeId,
                    targetKind,
                    (String) r.get("targetId"),
                    (List<String>) r.getOrDefault("orderedProfileIds", List.of()),
                    Boolean.TRUE.equals(r.get("enabled")),
                    1,
                    now,
                    now));
        }
    }
}
