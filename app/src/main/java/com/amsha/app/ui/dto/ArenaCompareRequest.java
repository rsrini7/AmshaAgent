package com.amsha.app.ui.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ArenaCompareRequest(
        @JsonProperty("prompt") String prompt,
        @JsonProperty("models") List<String> models,
        @JsonProperty("maxTokens") Integer maxTokens) {}
