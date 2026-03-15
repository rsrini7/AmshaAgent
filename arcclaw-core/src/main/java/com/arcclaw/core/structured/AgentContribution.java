package com.arcclaw.core.structured;

import io.quarkus.qute.TemplateData;

/**
 * Represents an individual agent's contribution to a synthesis task.
 */
@TemplateData
public record AgentContribution(
    String agentRole,
    String finding,
    double confidence
) {}
