package com.arcclaw.core.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for tiered memory (MemGPT-style Core/Recall/Archival tiers).
 * All features default to disabled — zero runtime behavior change until explicitly enabled.
 */
@ConfigMapping(prefix = "arcclaw.memory.tiered")
public interface TieredMemoryConfig {

    @WithDefault("false")
    boolean enabled();

    @WithDefault("sqlite")
    String backend();

    CoreMemoryConfig coreMemory();

    RecallConfig recall();

    ArchivalConfig archival();

    interface CoreMemoryConfig {
        @WithDefault("false")
        boolean enabled();

        @WithDefault("20")
        int maxBlocksPerUser();

        @WithDefault("2000")
        int maxCharsPerBlock();
    }

    interface RecallConfig {
        @WithDefault("false")
        boolean enabled();

        @WithDefault("90")
        int retentionDays();

        @WithDefault("10")
        int maxSearchResults();

        @WithDefault("true")
        boolean crossSessionSearch();
    }

    interface ArchivalConfig {
        @WithDefault("false")
        boolean enabled();

        @WithDefault("8")
        int maxSearchResults();

        @WithDefault("60")
        int hybridSearchK();

        @WithDefault("0.6")
        double confidenceThreshold();

        ArchivalEviction eviction();
    }

    interface ArchivalEviction {
        @WithDefault("60")
        int lowConfidenceDays();

        @WithDefault("0.5")
        double lowConfidenceThreshold();

        @WithDefault("1")
        int lruMinAccessCount();

        @WithDefault("90")
        int lruMaxAgeDays();
    }
}
