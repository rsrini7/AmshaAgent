package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.llm.api.ModelDefaults;
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
            connMaps.add(Map.ofEntries(
                    Map.entry("id", c.id()),
                    Map.entry("scopeType", c.scopeType()),
                    Map.entry("scopeId", c.scopeId()),
                    Map.entry("displayName", c.displayName() == null ? "" : c.displayName()),
                    Map.entry("providerAdapterId", c.providerAdapterId()),
                    Map.entry("endpointProfileId", c.endpointProfileId() == null ? "" : c.endpointProfileId()),
                    Map.entry("customBaseUrl", c.customBaseUrl() == null ? "" : c.customBaseUrl()),
                    Map.entry("networkProfileJson", c.networkProfileJson() == null ? "" : c.networkProfileJson()),
                    Map.entry("state", c.state())));
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
        return Map.of("version", 1, "connections", connMaps, "profiles", profileMaps, "routes", routeMaps);
    }

    private static final Set<String> VALID_ROUTE_TARGET_KINDS = Set.of(
            "DEFAULT", "ROLE", "GROUP", "AGENT_LOOP", "COMPACTION", "CLASSIFIER", "EMBEDDING", "PERSONA", "SYSTEM");

    public void validateImportPayload(String ownerId, Map<String, Object> data) {
        validateImportPayload(ownerId, data, null);
    }

    public void validateImportPayload(String ownerId, Map<String, Object> data, LlmProfileStorePort profileStore) {
        if (data == null || data.isEmpty()) {
            return;
        }
        Set<String> declaredConnectionIds = validateImportConnections(ownerId, data.get("connections"));
        Set<String> validConnectionIds = new HashSet<>(declaredConnectionIds);
        if (profileStore != null) {
            for (LlmConnectionDto c : profileStore.listConnectionsByCreatedBy(ownerId)) {
                validConnectionIds.add(c.id());
            }
        }
        Set<String> declaredProfileIds = validateImportProfiles(data.get("profiles"), validConnectionIds);
        Set<String> validProfileIds = new HashSet<>(declaredProfileIds);
        if (profileStore != null) {
            for (String connId : validConnectionIds) {
                for (ModelProfileDto p : profileStore.listModelProfilesByConnection(connId)) {
                    validProfileIds.add(p.id());
                }
            }
        }
        validateImportRoutes(ownerId, data.get("routes"), validProfileIds);
    }

    private static Set<String> validateImportConnections(String ownerId, Object rawConns) {
        Set<String> declaredConnectionIds = new HashSet<>();
        if (!(rawConns instanceof List<?> connections)) {
            return declaredConnectionIds;
        }
        for (Object item : connections) {
            if (!(item instanceof Map<?, ?> c)) continue;
            Object scopeType = c.get("scopeType");
            if (scopeType != null && !"USER".equalsIgnoreCase(String.valueOf(scopeType))) {
                throw new IllegalArgumentException("Imported connections cannot target non-user scope: " + scopeType);
            }
            Object scopeId = c.get("scopeId");
            if (scopeId != null && !ownerId.equals(String.valueOf(scopeId))) {
                throw new IllegalArgumentException(
                        "Imported connections cannot target another user's scope: " + scopeId);
            }
            Object id = c.get("id");
            if (id instanceof String s && !s.isBlank()) {
                declaredConnectionIds.add(s);
            }
        }
        return declaredConnectionIds;
    }

    private static Set<String> validateImportProfiles(Object rawProfiles, Set<String> validConnectionIds) {
        Set<String> declaredProfileIds = new HashSet<>();
        if (!(rawProfiles instanceof List<?> profiles)) {
            return declaredProfileIds;
        }
        for (Object item : profiles) {
            if (!(item instanceof Map<?, ?> p)) continue;
            Object connId = p.get("connectionId");
            if (connId != null && !validConnectionIds.contains(String.valueOf(connId))) {
                throw new IllegalArgumentException("Imported profile references unknown connectionId: " + connId);
            }
            Object id = p.get("id");
            if (id instanceof String s && !s.isBlank()) {
                declaredProfileIds.add(s);
            }
        }
        return declaredProfileIds;
    }

    private static void validateImportRoutes(String ownerId, Object rawRoutes, Set<String> validProfileIds) {
        if (!(rawRoutes instanceof List<?> routes)) {
            return;
        }
        for (Object item : routes) {
            if (!(item instanceof Map<?, ?> r)) continue;
            Object targetKind = r.get("targetKind");
            if (targetKind != null && !VALID_ROUTE_TARGET_KINDS.contains(String.valueOf(targetKind))) {
                throw new IllegalArgumentException("Imported route has invalid targetKind: " + targetKind);
            }
            Object scopeType = r.get("scopeType");
            if (scopeType != null && !"USER".equalsIgnoreCase(String.valueOf(scopeType))) {
                throw new IllegalArgumentException("Imported routes cannot target non-user scope: " + scopeType);
            }
            Object scopeId = r.get("scopeId");
            if (scopeId != null && !ownerId.equals(String.valueOf(scopeId))) {
                throw new IllegalArgumentException("Imported routes cannot target another user's scope: " + scopeId);
            }
            Object ordered = r.get("orderedProfileIds");
            if (ordered instanceof List<?> orderedList) {
                for (Object o : orderedList) {
                    if (o != null && !validProfileIds.contains(String.valueOf(o))) {
                        throw new IllegalArgumentException("Imported route references unknown profileId: " + o);
                    }
                }
            }
        }
    }

    public void importProfiles(String ownerId, Map<String, Object> data, LlmProfileStorePort profileStore) {
        importProfiles(ownerId, data, profileStore, false);
    }

    public void importProfiles(
            String ownerId, Map<String, Object> data, LlmProfileStorePort profileStore, boolean active) {
        if (data == null || profileStore == null) {
            return;
        }
        validateImportPayload(ownerId, data, profileStore);
        Instant now = Instant.now();
        importConnections(ownerId, data.get("connections"), profileStore, now, active ? "ACTIVE" : "STAGED");
        importModelProfiles(data.get("profiles"), profileStore, now, active);
        importRoutes(ownerId, data.get("routes"), profileStore, now, active);
    }

    private static void importConnections(
            String ownerId, Object rawConnections, LlmProfileStorePort profileStore, Instant now, String state) {
        if (!(rawConnections instanceof List<?> connections)) return;
        for (Object item : connections) {
            if (!(item instanceof Map<?, ?> c)) continue;
            String connId = c.get("id") instanceof String s ? s : null;
            if (connId == null || connId.isBlank()) continue;
            String displayName = c.get("displayName") instanceof String s ? s : connId;
            String providerAdapterId = c.get("providerAdapterId") instanceof String s
                    ? s
                    : (c.get("provider") instanceof String prov ? prov : "openai");
            String endpointProfileId = c.get("endpointProfileId") instanceof String s && !s.isBlank() ? s : null;
            String customBaseUrl = c.get("customBaseUrl") instanceof String s && !s.isBlank()
                    ? s
                    : (c.get("baseUrl") instanceof String b && !b.isBlank() ? b : null);
            String authScheme = c.get("authScheme") instanceof String s && !s.isBlank() ? s : "API_KEY";
            String networkProfileJson = c.get("networkProfileJson") instanceof String s && !s.isBlank() ? s : null;
            String effectiveState = state != null ? state : (c.get("state") instanceof String s ? s : "STAGED");
            profileStore.saveConnection(new LlmConnectionDto(
                    connId,
                    "USER",
                    ownerId,
                    ownerId,
                    displayName,
                    providerAdapterId,
                    endpointProfileId,
                    customBaseUrl,
                    authScheme,
                    networkProfileJson,
                    effectiveState,
                    1,
                    now,
                    now));
        }
    }

    private static void importModelProfiles(
            Object rawProfiles, LlmProfileStorePort profileStore, Instant now, boolean enabled) {
        if (!(rawProfiles instanceof List<?> profiles)) return;
        for (Object item : profiles) {
            if (!(item instanceof Map<?, ?> p)) continue;
            String profileId = p.get("id") instanceof String s ? s : null;
            String connId = p.get("connectionId") instanceof String s ? s : null;
            if (profileId == null || connId == null) continue;
            String modelId = p.get("modelId") instanceof String s ? s : ModelDefaults.DEFAULT_OPENAI_MODEL;
            String structuredOutputMode = p.get("structuredOutputMode") instanceof String s ? s : "NATIVE";
            profileStore.saveModelProfile(new ModelProfileDto(
                    profileId, connId, modelId, structuredOutputMode, null, null, enabled, 1, now, now));
        }
    }

    private static void importRoutes(
            String ownerId, Object rawRoutes, LlmProfileStorePort profileStore, Instant now, boolean enabled) {
        if (!(rawRoutes instanceof List<?> routes)) return;
        for (Object item : routes) {
            if (!(item instanceof Map<?, ?> r)) continue;
            String routeId = r.get("id") instanceof String s ? s : null;
            String targetKind = r.get("targetKind") instanceof String s ? s : null;
            if (routeId == null || targetKind == null) continue;
            String targetId = r.get("targetId") instanceof String s ? s : null;
            List<String> orderedProfileIds = List.of();
            if (r.get("orderedProfileIds") instanceof List<?> rawOrdered) {
                orderedProfileIds = rawOrdered.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
            }
            profileStore.saveRouteAssignment(new ModelRouteAssignmentDto(
                    routeId, "USER", ownerId, targetKind, targetId, orderedProfileIds, enabled, 1, now, now));
        }
    }
}
