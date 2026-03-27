package com.arcclaw.core.util;

import java.util.List;
import java.util.Map;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.state.AgentCheckpoint;
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
        if (!cp.hasBatchToolDecisions()) return List.of();
        try {
            Map<String, String> map = objectMapper.readValue(
                cp.getBatchToolDecisions(),
                new TypeReference<>() {});
            return map.entrySet().stream()
                .filter(e -> AgentSentinels.TOOL_DECISION_PENDING.equals(e.getValue()))
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
        if (!cp.hasBatchToolDecisions()) return List.of();
        try {
            Map<String, String> map = objectMapper.readValue(
                cp.getBatchToolDecisions(),
                new TypeReference<>() {});
            return map.keySet().stream().toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
