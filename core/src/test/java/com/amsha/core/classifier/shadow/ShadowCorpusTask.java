package com.amsha.core.classifier.shadow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Standard decision tasks evaluated in the shadow corpus.
 */
public enum ShadowCorpusTask {
    INTENT("intent"),
    TOOL_RISK("tool_risk"),
    ANSWER_QUALITY("answer_quality");

    private final String taskName;

    ShadowCorpusTask(String taskName) {
        this.taskName = taskName;
    }

    @JsonValue
    public String taskName() {
        return taskName;
    }

    @JsonCreator
    public static ShadowCorpusTask fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (ShadowCorpusTask t : values()) {
            if (t.taskName.equalsIgnoreCase(value) || t.name().equalsIgnoreCase(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown shadow corpus task: " + value);
    }
}
