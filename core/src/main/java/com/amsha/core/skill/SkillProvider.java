package com.amsha.core.skill;

import java.util.List;

/**
 * SPI for skill source discovery.
 * Implementations provide skill definitions from various sources (classpath, DB, etc.).
 * <p>
 * Providers declare a {@link #priority()} to ensure deterministic merge order
 * regardless of CDI {@code Instance} iteration order. Lower value = higher priority.
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

    /**
     * Merge priority. Lower value = higher priority (processed first, wins on collision).
     * <p>Database = 10 (user-created overrides), Classpath = 100 (bundled baseline).
     * Custom providers should use values between 10 and 100.
     *
     * @return priority value, default 100
     */
    default int priority() {
        return 100;
    }
}
