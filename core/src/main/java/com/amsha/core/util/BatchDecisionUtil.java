package com.amsha.core.util;

import java.util.List;
import java.util.Map;

import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.persistence.api.ToolDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Utility for reading per-tool decision state from AgentCheckpoint.
 * Eliminates duplication across ApproveReflex, DenyReflex, WorkspaceManager.
 */
public final class BatchDecisionUtil {

    private BatchDecisionUtil() {}

    /**
     * Returns tool names whose decision is still PENDING. §7.20
     */
    public static List<String> pendingToolNames(AgentCheckpoint cp, ObjectMapper objectMapper) {
        if (cp == null || !cp.hasBatchToolDecisions()) return List.of();
        try {
            Map<String, ToolDecision> map =
                    objectMapper.readValue(cp.getBatchToolDecisions(), new TypeReference<>() {});
            return map.entrySet().stream()
                    .filter(e -> ToolDecision.PENDING == e.getValue())
                    .map(Map.Entry::getKey)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Returns all tool names regardless of decision status.
     */
    public static List<String> allToolNames(AgentCheckpoint cp, ObjectMapper objectMapper) {
        if (cp == null || !cp.hasBatchToolDecisions()) return List.of();
        try {
            Map<String, ToolDecision> map =
                    objectMapper.readValue(cp.getBatchToolDecisions(), new TypeReference<>() {});
            return map.keySet().stream().toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
