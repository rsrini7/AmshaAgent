package com.amsha.mcp.transport;

import java.time.Instant;

/** Non-secret MCP credential header metadata safe for transport diagnostics. */
public record HeaderMetadata(String headerName, boolean credentialConfigured, Instant createdAt, Instant updatedAt) {

    public HeaderMetadata {
        if (headerName == null || headerName.isBlank()) throw new IllegalArgumentException("headerName is required");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }
}
