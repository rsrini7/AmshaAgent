package com.amsha.core.agent.loop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/** Bridges a completed delegated child back into the awaiting parent batch. */
final class DelegatedCompletionHandoff {

    private static final Logger log = Logger.getLogger(DelegatedCompletionHandoff.class);

    private final StatefulAgentStore agentStore;
    private final ObjectMapper objectMapper;

    DelegatedCompletionHandoff(StatefulAgentStore agentStore, ObjectMapper objectMapper) {
        this.agentStore = agentStore;
        this.objectMapper = objectMapper;
    }

    Optional<ParentResume> capture(String childSessionId, AgentResult result) {
        if (!isTerminal(result) || childSessionId == null) {
            return Optional.empty();
        }
        int marker = childSessionId.indexOf(AgentSentinels.DELEGATE_SESSION_SENTINEL);
        if (marker <= 0) {
            return Optional.empty();
        }

        String parentSessionId = childSessionId.substring(0, marker);
        Optional<AgentCheckpoint> parentOpt = findActiveParent(parentSessionId);
        if (parentOpt.isEmpty()) {
            return Optional.empty();
        }

        AgentCheckpoint parent = parentOpt.get();
        if (!parent.isParallelBatch()) {
            return captureSequential(parent, childSessionId, result);
        }

        List<ParallelToolCall> calls = readCalls(parent.getPendingToolParametersJson());
        ParallelToolCall childCall = findChildCall(calls, childSessionId, parent.getPreExecutedResults());
        if (childCall == null) {
            log.warnf("[DELEGATION] No parent batch call matched child session %s", childSessionId);
            return Optional.empty();
        }

        Map<String, JsonNode> results = readResults(parent.getPreExecutedResults());
        String callKey = callKey(childCall);
        if (results.containsKey(callKey) || results.containsKey(childCall.toolName())) {
            return Optional.empty();
        }
        try {
            String responseJson = objectMapper.writerFor(ToolResponse.class).writeValueAsString(toToolResponse(result));
            results.put(callKey, objectMapper.readTree(responseJson));
            parent.setPreExecutedResults(objectMapper.writeValueAsString(results));
            agentStore.save(parent);
            boolean ready = !hasActiveDescendants(parentSessionId);
            return Optional.of(parentResume(parent, ready));
        } catch (Exception e) {
            log.warnf(
                    "[DELEGATION] Could not persist child result for parent %s: %s",
                    parentSessionId, LogUtil.rootMessage(e));
            return Optional.empty();
        }
    }

    /**
     * Completes a single delegated call for a sequential parent.  Sequential
     * delegation does not have a parallel result map, so the parent must be
     * given the child observation and its pending delegation cleared before it
     * is resumed for terminal synthesis.
     */
    private Optional<ParentResume> captureSequential(
            AgentCheckpoint parent, String childSessionId, AgentResult result) {
        if (!ToolCapability.DELEGATE_TO_AGENT.toolName().equals(parent.getPendingToolName())) {
            return Optional.empty();
        }

        List<ReasoningStep> history = agentStore.deserializeSteps(parent);
        if (history == null) {
            history = new ArrayList<>();
        } else {
            history = new ArrayList<>(history);
        }
        ToolResponse response = toToolResponse(result);
        history.add(ReasoningStep.observeStep(
                ToolCapability.DELEGATE_TO_AGENT.toolName(),
                response.output(),
                "Delegated agent completed; synthesize the result."));

        parent.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        parent.setPendingToolName(null);
        parent.setPendingToolRole(null);
        parent.setPendingToolParametersJson(null);
        parent.setBatchToolDecisions(null);
        parent.setPreExecutedResults(null);
        agentStore.saveWithSteps(parent, history);

        log.infof(
                "[DELEGATION] Captured sequential child %s for parent %s; parent is ready to synthesize",
                childSessionId, parent.getSessionId());
        return Optional.of(parentResume(parent, true));
    }

    private Optional<AgentCheckpoint> findActiveParent(String parentSessionId) {
        Optional<AgentCheckpoint> awaiting = agentStore.findLatestAwaitingApproval(parentSessionId);
        if (awaiting.isPresent()) {
            return awaiting;
        }
        return agentStore
                .findLatestCheckpoint(parentSessionId)
                .filter(cp ->
                        cp.getStatus() == CheckpointStatus.RESUMING || cp.getStatus() == CheckpointStatus.IN_PROGRESS);
    }

    private ParentResume parentResume(AgentCheckpoint parent, boolean ready) {
        String userId = parent.getChannelPlatformId();
        String chatId = parent.getChannelPlatformId();
        if (parent.getContextDataJson() != null && !parent.getContextDataJson().isBlank()) {
            try {
                Map<String, Object> data =
                        objectMapper.readValue(parent.getContextDataJson(), new TypeReference<>() {});
                if (data != null) {
                    if (data.get("userId") instanceof String u && !u.isBlank()) {
                        userId = u;
                    }
                    if (data.get("chatId") instanceof String c && !c.isBlank()) {
                        chatId = c;
                    }
                }
            } catch (Exception e) {
                log.debugf("Failed to read userId/chatId from parent context data: %s", LogUtil.rootMessage(e));
            }
        }
        return new ParentResume(
                parent.getSessionId(), parent.getAgentRole(), userId, chatId, parent.getChannelType(), ready);
    }

    private List<ParallelToolCall> readCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ParallelToolCall>>() {});
        } catch (Exception e) {
            log.warnf("[DELEGATION] Invalid parent batch payload: %s", LogUtil.rootMessage(e));
            return List.of();
        }
    }

    private Map<String, JsonNode> readResults(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, JsonNode> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            log.warnf("[DELEGATION] Invalid parent pre-executed results: %s", LogUtil.rootMessage(e));
            return new LinkedHashMap<>();
        }
    }

    private ParallelToolCall findChildCall(List<ParallelToolCall> calls, String childSessionId, String resultsJson) {
        String childAgent = childAgent(childSessionId);
        Map<String, JsonNode> results = readResults(resultsJson);
        for (ParallelToolCall call : calls) {
            if (!results.containsKey(callKey(call))
                    && !results.containsKey(call.toolName())
                    && callTargetsAgent(call, childAgent)) {
                return call;
            }
        }
        if (calls.size() == 1) {
            ParallelToolCall only = calls.getFirst();
            return results.containsKey(callKey(only)) || results.containsKey(only.toolName()) ? null : only;
        }
        return null;
    }

    private boolean hasActiveDescendants(String parentSessionId) {
        String prefix = parentSessionId + AgentSentinels.DELEGATE_SESSION_SENTINEL;
        for (var status :
                List.of(CheckpointStatus.AWAITING_APPROVAL, CheckpointStatus.RESUMING, CheckpointStatus.IN_PROGRESS)) {
            if (agentStore.findByStatus(status).stream()
                    .anyMatch(cp -> cp != null
                            && cp.getSessionId() != null
                            && cp.getSessionId().startsWith(prefix))) {
                return true;
            }
        }
        return false;
    }

    private boolean callTargetsAgent(ParallelToolCall call, String childAgent) {
        if (childAgent == null || call.params() == null) {
            return false;
        }
        for (String key : List.of("agent", "persona", "target")) {
            Object value = call.params().get(key);
            if (value != null && childAgent.equalsIgnoreCase(String.valueOf(value))) {
                return true;
            }
        }
        return false;
    }

    private static String childAgent(String childSessionId) {
        int marker = childSessionId.indexOf(AgentSentinels.DELEGATE_SESSION_SENTINEL);
        if (marker < 0) {
            return null;
        }
        String suffix = childSessionId.substring(marker + AgentSentinels.DELEGATE_SESSION_SENTINEL.length());
        int end = suffix.indexOf(':');
        return end < 0 ? suffix : suffix.substring(0, end);
    }

    private static String callKey(ParallelToolCall call) {
        return call.callId() == null || call.callId().isBlank() ? call.toolName() : call.callId();
    }

    private static boolean isTerminal(AgentResult result) {
        return result instanceof AgentResult.Finished
                || result instanceof AgentResult.StructuredFinished
                || result instanceof AgentResult.Failed;
    }

    private static ToolResponse toToolResponse(AgentResult result) {
        return switch (result) {
            case AgentResult.Finished finished -> ToolResponse.success(finished.answer());
            case AgentResult.StructuredFinished finished -> ToolResponse.success(finished.answer());
            case AgentResult.Failed failed -> ToolResponse.failure(1, "Sub-agent failed: " + failed.reason());
            default -> throw new IllegalArgumentException("Non-terminal delegated result");
        };
    }

    record ParentResume(
            String sessionId, String agentRole, String userId, String chatId, String channelType, boolean ready) {}
}
