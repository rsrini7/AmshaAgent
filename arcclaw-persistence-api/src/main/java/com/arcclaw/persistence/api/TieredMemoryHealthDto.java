package com.arcclaw.persistence.api;

/**
 * Transfer object for tiered memory health status.
 */
public record TieredMemoryHealthDto(
    boolean healthy,
    String backend,
    String detail,
    int coreBlockCount,
    long recallMessageCount,
    long archivalChunkCount
) {}
