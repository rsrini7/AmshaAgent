package com.amsha.app.ui.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DbQueryResponse(
        @JsonProperty("columns") List<String> columns,
        @JsonProperty("rows") List<List<Object>> rows,
        @JsonProperty("executionMs") long executionMs,
        @JsonProperty("truncated") boolean truncated) {}
