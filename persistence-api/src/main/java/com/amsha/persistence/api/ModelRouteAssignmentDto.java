package com.amsha.persistence.api;

import java.time.Instant;
import java.util.List;

/**
 * Durable, non-secret ordered model-profile chain assigned to a scope/role/group.
 */
public record ModelRouteAssignmentDto(
        String id,
        String scopeType,
        String scopeId,
        String targetKind,
        String targetId,
        List<String> orderedProfileIds,
        boolean enabled,
        int revision,
        Instant createdAt,
        Instant updatedAt) {

    public ModelRouteAssignmentDto {
        requireText(id, "id");
        requireText(scopeType, "scopeType");
        requireText(scopeId, "scopeId");
        requireText(targetKind, "targetKind");
        if (orderedProfileIds == null) orderedProfileIds = List.of();
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
