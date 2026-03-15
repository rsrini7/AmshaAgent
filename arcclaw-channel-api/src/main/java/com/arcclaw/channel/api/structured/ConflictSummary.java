package com.arcclaw.channel.api.structured;

/**
 * Structured summary of conflicts between specialist agents.
 */
public record ConflictSummary(
    String conflictType,
    String description,
    String resolutionProposal
) {}
