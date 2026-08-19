package com.amsha.llm.api;

/**
 * A single model advertised by a provider descriptor. Immutable; the compact constructor validates
 * that neither identifier is blank and that tier constraints (if present) are well-formed.
 */
public record ModelEntry(String id, String label, String apiTier) {

    public ModelEntry {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("model id is required");
        if (label == null || label.isBlank()) label = id;
        if (apiTier != null && apiTier.isBlank()) apiTier = null;
    }

    /** Convenience constructor with no API-tier constraint. */
    public ModelEntry(String id, String label) {
        this(id, label, null);
    }
}
