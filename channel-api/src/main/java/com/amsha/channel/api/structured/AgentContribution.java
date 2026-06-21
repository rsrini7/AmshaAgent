package com.amsha.channel.api.structured;

import java.util.Map;

/**
 * Capture of a single specialist agent's findings for the synthesizer.
 */
public record AgentContribution(String agentRole, String finding, Map<String, Object> metadata) {}
