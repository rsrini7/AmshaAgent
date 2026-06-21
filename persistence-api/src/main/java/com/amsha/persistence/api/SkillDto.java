package com.amsha.persistence.api;

import java.time.Instant;

/**
 * Transfer object for agent skills.
 * §1.2: Core-persistence boundary.
 */
public record SkillDto(String id, String name, String definitionYaml, Instant createdAt, Instant updatedAt) {}
