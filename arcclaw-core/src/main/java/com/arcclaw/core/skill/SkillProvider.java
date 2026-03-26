package com.arcclaw.core.skill;

import java.util.List;

/**
 * SPI for skill source discovery.
 * Implementations provide skill definitions from various sources (classpath, DB, etc.).
 */
public interface SkillProvider {
    /** 
     * Loads skill definitions from this provider's source.
     * @return List of raw skill definitions.
     */
    List<SkillDefinition> load();

    /** 
     * Returns the name of the source (for logging/metadata).
     */
    String sourceName();
}
