package com.amsha.mcp.transport;

import java.time.Instant;

import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;

/** Non-secret reference to a vault-backed MCP HTTP header credential. */
public record HeaderSecretReference(
        String id,
        String connectionId,
        String userId,
        String headerName,
        String vaultKeyRef,
        Instant createdAt,
        Instant updatedAt) {

    public HeaderSecretReference {
        requireText(id, "id");
        requireText(connectionId, "connectionId");
        requireText(userId, "userId");
        requireText(headerName, "headerName");
        requireText(vaultKeyRef, "vaultKeyRef");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }

    static HeaderSecretReference from(McpConnectionHeaderSecretRefDto dto) {
        return new HeaderSecretReference(
                dto.id(),
                dto.connectionId(),
                dto.userId(),
                dto.headerName(),
                dto.vaultKeyRef(),
                dto.createdAt(),
                dto.updatedAt());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
