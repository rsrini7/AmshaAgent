package com.amsha.core.classifier.shadow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Standard test partitions evaluated across the shadow decision corpus.
 * Represents stress-testing categories ensuring comprehensive edge-case and robustness coverage.
 */
public enum ShadowCorpusPartition {
    STANDARD("standard"),
    NEGATION("negation"),
    CONTRADICTORY_CRITERIA("contradictory_criteria"),
    HOSTILE_INJECTED_STATE("hostile_injected_state"),
    IRRELEVANT_CONTEXT("irrelevant_context"),
    MALFORMED_PARTIAL_PAYLOAD("malformed_partial_payload"),
    DUPLICATE_IDENTIFIERS("duplicate_identifiers"),
    SHORTLIST_CHANGES("shortlist_changes"),
    STATE_VS_CRITERIA_PLACEMENT("state_vs_criteria_placement");

    private final String partitionName;

    ShadowCorpusPartition(String partitionName) {
        this.partitionName = partitionName;
    }

    @JsonValue
    public String partitionName() {
        return partitionName;
    }

    @JsonCreator
    public static ShadowCorpusPartition fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (ShadowCorpusPartition p : values()) {
            if (p.partitionName.equalsIgnoreCase(value) || p.name().equalsIgnoreCase(value)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown shadow corpus partition: " + value);
    }
}
