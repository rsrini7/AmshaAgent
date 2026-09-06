package com.amsha.app.ui.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ArenaCompareResponse(
        @JsonProperty("results") List<ArenaModelResult> results,
        @JsonProperty("totalDurationMs") long totalDurationMs) {}
