package com.amsha.core.skill;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Intermediate Jackson DTO representing the YAML frontmatter of a skill definition file.
 */
public record SkillFrontmatter(
        String name,
        String description,

        @JsonProperty("trigger-patterns") List<String> triggerPatterns,

        @JsonProperty("tool-capabilities") List<String> toolCapabilities,

        @JsonProperty("allowed-tools") String allowedTools,

        /** Explicitly requests browser preview handling for generated artifacts. */
        @JsonProperty("browser-preview") boolean browserPreview,

        @JsonProperty("template-dir") String templateDir,

        @JsonProperty("max-iterations") int maxIterations) {}
