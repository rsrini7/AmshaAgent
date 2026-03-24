package com.arcclaw.core.skill;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Intermediate Jackson DTO representing the YAML frontmatter of a skill definition file.
 */
public record SkillFrontmatter(
    String name,
    String description,
    
    @JsonProperty("trigger-patterns") 
    List<String> triggerPatterns,
    
    @JsonProperty("tool-capabilities") 
    List<String> toolCapabilities,
    
    @JsonProperty("max-iterations") 
    int maxIterations
) {}
