package com.amsha.memory;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * GraalVM Native Image reflection configuration for amsha-memory.
 * Memory types moved here from core (extract-core-subsystems S8); no entries were
 * present in {@code CoreReflectionConfig} for these types (verified during S8).
 */
@RegisterForReflection
public class MemoryReflectionConfig {}
