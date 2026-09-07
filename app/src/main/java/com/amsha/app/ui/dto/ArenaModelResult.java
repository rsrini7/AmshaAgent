package com.amsha.app.ui.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of evaluating an individual model in the Model Arena.
 * Note: {@code costUsd} is an estimated USD cost based on token usage and standard rates.
 */
public record ArenaModelResult(
        @JsonProperty("modelSlot") String modelSlot,
        @JsonProperty("output") String output,
        @JsonProperty("inputTokens") int inputTokens,
        @JsonProperty("outputTokens") int outputTokens,
        @JsonProperty("durationMs") long durationMs,
        @JsonProperty("costUsd") double costUsd) {}
