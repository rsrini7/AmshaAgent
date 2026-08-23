package com.amsha.memory.tiered;

import com.amsha.persistence.api.TieredBackend;

/**
 * Supported tiered memory backends.
 * Selected programmatically via {@code TieredBackendLiteral} and the {@link TieredBackend} qualifier.
 */
public enum TieredMemoryBackend {
    SQLITE("sqlite"),
    POSTGRES("postgres"),
    FILESYSTEM("fs");

    private final String id;

    TieredMemoryBackend(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static TieredMemoryBackend fromId(String id) {
        for (TieredMemoryBackend b : values()) {
            if (b.id.equalsIgnoreCase(id)) {
                return b;
            }
        }
        throw new IllegalArgumentException(
                "Unknown tiered memory backend: " + id + ". Valid values: sqlite, postgres, fs");
    }
}
