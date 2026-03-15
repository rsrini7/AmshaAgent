package com.arcclaw.core.structured;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.qute.TemplateData;
import jakarta.annotation.Nullable;

/**
 * The typed output of the DattatreyaAgent synthesis process.
 */
@TemplateData
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SynthesisResult(
    @JsonProperty(required = true)
    List<AgentContribution> contributions,

    @JsonProperty(required = true)
    String resolution,

    @Nullable
    ConflictSummary conflicts
) {}
