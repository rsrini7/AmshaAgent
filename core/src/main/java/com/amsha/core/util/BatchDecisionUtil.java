package com.amsha.core.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ToolDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Utility for reading per-tool decision state from AgentCheckpoint.
 * Eliminates duplication across ApproveReflex, DenyReflex, WorkspaceManager.
 */
public final class BatchDecisionUtil {

    private BatchDecisionUtil() {}

    /**
     * Converts a list of parallel tool calls into a structured, readable map for approval display.
     */
    public static Map<String, Object> toDisplayMap(List<ParallelToolCall> calls) {
        if (calls == null || calls.isEmpty()) return Map.of();
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < calls.size(); i++) {
            ParallelToolCall call = calls.get(i);
            String label = (i + 1) + ". " + call.toolName();
            map.put(label, call.params() != null ? call.params() : Map.of());
        }
        return Collections.unmodifiableMap(map);
    }

    /** Stable key for one parallel invocation's approval decision. */
    public static String decisionKey(ParallelToolCall call) {
        if (call == null) return null;
        return call.callId() == null || call.callId().isBlank() ? call.toolName() : call.callId();
    }

    /** Matches checkpoint keying when a provider omitted call IDs. */
    public static String decisionKey(ParallelToolCall call, int index, List<ParallelToolCall> calls) {
        if (call == null) return null;
        long sameName = calls == null
                ? 1
                : calls.stream()
                        .filter(candidate -> candidate != null && Objects.equals(candidate.toolName(), call.toolName()))
                        .count();
        // Keep the persisted format user-facing for the normal case. A call ID
        // is needed only when the same tool occurs more than once in a batch;
        // otherwise a tool-name key is both backwards-compatible and what the
        // approval commands expose to users.
        if (sameName == 1) return call.toolName();
        if (call.callId() != null && !call.callId().isBlank()) return call.callId();
        return sameName > 1 ? call.toolName() + "_" + index : call.toolName();
    }

    /** Looks up a decision by call id, retaining compatibility with old tool-name maps. */
    public static ToolDecision decisionFor(Map<String, ToolDecision> decisions, ParallelToolCall call) {
        if (decisions == null || call == null) return ToolDecision.PENDING;
        String key = decisionKey(call);
        ToolDecision decision = key == null ? null : decisions.get(key);
        return decision != null ? decision : decisions.getOrDefault(call.toolName(), ToolDecision.PENDING);
    }

    /**
     * Parses pending tool parameters JSON (either single-tool map, batch array, or batch envelope) into a structured display map.
     */
    public static Map<String, Object> parseBatchDisplayParams(String rawJson, ObjectMapper objectMapper) {
        if (rawJson == null || rawJson.isBlank() || "{}".equals(rawJson.trim()) || "[]".equals(rawJson.trim())) {
            return Map.of();
        }
        if (objectMapper == null) return Map.of("batch", rawJson);
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            if (root.isArray()) {
                Map<String, Object> map = new LinkedHashMap<>();
                int idx = 1;
                for (JsonNode node : root) {
                    String toolName = node.has("toolName")
                            ? node.get("toolName").asText()
                            : (node.has("tool") ? node.get("tool").asText() : "tool_" + idx);
                    JsonNode paramsNode = node.get("params");
                    Object paramsObj = paramsNode != null
                            ? objectMapper.convertValue(paramsNode, new TypeReference<Map<String, Object>>() {})
                            : Map.of();
                    map.put(idx + ". " + toolName, paramsObj);
                    idx++;
                }
                return Collections.unmodifiableMap(map);
            }
            if (root.isObject()) {
                if (root.has("batch") && root.get("batch").isArray()) {
                    return parseBatchDisplayParams(root.get("batch").toString(), objectMapper);
                }
                return objectMapper.convertValue(root, new TypeReference<Map<String, Object>>() {});
            }
            return Map.of("batch", rawJson);
        } catch (Exception _) {
            return Map.of("batch", rawJson);
        }
    }

    /** Strict validation for payloads that control an approval/resume transition. */
    public static boolean isValidBatchParamsJson(String rawJson, ObjectMapper objectMapper) {
        if (rawJson == null || rawJson.isBlank() || objectMapper == null) return false;
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            if (root == null || !(root.isObject() || root.isArray())) return false;
            if (root.isArray()) {
                for (JsonNode element : root) {
                    if (element == null || !element.isObject()) return false;
                    JsonNode params = element.get("params");
                    if (params != null && !params.isObject()) return false;
                }
            }
            return true;
        } catch (Exception _) {
            return false;
        }
    }

    /**
     * Normalizes a tool name argument from HITL approval requests.
     * Strips "parallel|" sentinel prefix and maps "all" or multi-tool composite names to "all".
     */
    public static String normalizeToolName(String toolName) {
        if (toolName == null) return null;
        String trimmed = toolName.trim();
        if (trimmed.startsWith(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX)) {
            trimmed = trimmed.substring(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX.length())
                    .trim();
        }
        if ("all".equalsIgnoreCase(trimmed) || trimmed.contains(",") || trimmed.contains("|")) {
            return "all";
        }
        return trimmed;
    }

    /**
     * Returns tool names whose decision is still PENDING. §7.20
     */
    public static List<String> pendingToolNames(AgentCheckpoint cp, ObjectMapper objectMapper) {
        if (cp == null) return List.of();
        if (!cp.hasBatchToolDecisions()) {
            if (cp.getPendingToolName() != null
                    && !cp.getPendingToolName().isBlank()
                    && cp.getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                return List.of(cp.getPendingToolName());
            }
            return List.of();
        }
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
     * Returns whether a checkpoint still contains an unresolved decision.
     * Parse failures are treated as unresolved so a corrupt approval record
     * cannot accidentally reach a resume path.
     */
    public static boolean hasPendingToolNames(AgentCheckpoint cp, ObjectMapper objectMapper) {
        if (cp == null) return false;
        if (!cp.hasBatchToolDecisions()) {
            return cp.getStatus() == CheckpointStatus.AWAITING_APPROVAL
                    && cp.getPendingToolName() != null
                    && !cp.getPendingToolName().isBlank();
        }
        if (objectMapper == null) return true;
        try {
            Map<String, ToolDecision> map =
                    objectMapper.readValue(cp.getBatchToolDecisions(), new TypeReference<>() {});
            return map == null || map.values().stream().anyMatch(ToolDecision.PENDING::equals);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Returns all tool names regardless of decision status.
     */
    public static List<String> allToolNames(AgentCheckpoint cp, ObjectMapper objectMapper) {
        if (cp == null) return List.of();
        if (!cp.hasBatchToolDecisions()) {
            if (cp.getPendingToolName() != null && !cp.getPendingToolName().isBlank()) {
                return List.of(cp.getPendingToolName());
            }
            return List.of();
        }
        try {
            Map<String, ToolDecision> map =
                    objectMapper.readValue(cp.getBatchToolDecisions(), new TypeReference<>() {});
            return map.keySet().stream().toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
