package com.amsha.channel.api.structured;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Final synthesized result emitted by DattatreyaAgent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SynthesisResult(
        @JsonProperty(required = true) List<AgentContribution> contributions,
        @JsonProperty(required = true) List<ConflictSummary> conflictSummary,
        @JsonProperty(required = true) String finalResolution,
        Map<String, Object> additionalInfo) {}
