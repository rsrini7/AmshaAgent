package com.arcclaw.core.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for self-evolution (Hermes-style trace capture, extraction, directive synthesis).
 * All features default to disabled — zero runtime behavior change until explicitly enabled.
 */
@ConfigMapping(prefix = "arcclaw.self-evolve")
public interface SelfEvolveConfig {

    @WithDefault("false")
    boolean enabled();

    /**
     * Backend for self-evolve trace storage and extracted records.
     * Valid values: sqlite, postgres, fs.
     */
    @WithDefault("sqlite")
    String backend();

    @WithDefault("50")
    int bufferSize();

    @WithDefault("8")
    int defaultSearchLimit();

    /**
     * Whether synthesized directives can be injected into prompts.
     * Only matters when {@link #enabled()} is true.
     * Default true so directives flow through shadow lifecycle when self-evolve is on.
     */
    @WithDefault("true")
    boolean directivesEnabled();

    /**
     * Synthesis trigger mode.
     * <ul>
     *   <li>{@code manual} — only via explicit {@code self_evolve_reflect} tool or admin API</li>
     *   <li>{@code cron} — periodic scheduled synthesis (daily)</li>
     * </ul>
     * Must not be "auto" in the live request path.
     */
    @WithDefault("manual")
    String synthesisTrigger();

    FlushConfig flush();

    interface FlushConfig {
        @WithDefault("12")
        int maxEvents();

        @WithDefault("300000")
        long idleMs();
    }
}
