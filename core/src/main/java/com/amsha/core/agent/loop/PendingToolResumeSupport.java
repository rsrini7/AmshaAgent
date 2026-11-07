package com.amsha.core.agent.loop;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tool.attempt.ToolOperationIdentity;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ToolDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/** Parsing and delegation helpers for pending tool resume. */
final class PendingToolResumeSupport {
    private static final Logger log = Logger.getLogger(PendingToolResumeSupport.class);
    private static final TypeReference<Map<String, Object>> PARAMETER_MAP = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final StatefulAgentStore agentStore;

    PendingToolResumeSupport(ObjectMapper objectMapper, StatefulAgentStore agentStore) {
        this.objectMapper = objectMapper;
        this.agentStore = agentStore;
    }

    Map<String, Object> parseParams(String paramsJson) throws Exception {
        if (paramsJson == null || paramsJson.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(paramsJson, PARAMETER_MAP);
    }

    Map<String, Object> parsePausedParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank() || "{}".equals(paramsJson.trim())) {
            return Map.of();
        }
        try {
            return parseParams(paramsJson);
        } catch (Exception e) {
            log.warnf("Failed to parse delegated approval parameters: %s", LogUtil.rootMessage(e));
            return null;
        }
    }

    String buildDelegationParams(String toolName, String paramsJson, String owner) {
        Map<String, Object> params = Map.of();
        if (paramsJson != null && !paramsJson.isBlank()) {
            try {
                params = parseParams(paramsJson);
            } catch (Exception e) {
                log.warnf("[ResumeStrategy] Could not parse pending %s: %s", toolName, LogUtil.rootMessage(e));
            }
        }
        try {
            return objectMapper.writeValueAsString(
                    Map.of("agent", owner, "goal", ToolStepResolutionUtils.buildDelegateGoal(toolName, params)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not build delegation request for " + toolName, e);
        }
    }

    String extractUrlFromParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(paramsJson);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode url = root.get("url");
            return url != null && url.isTextual() && !url.textValue().isBlank() ? url.textValue() : null;
        } catch (Exception _) {
            return null;
        }
    }

    Replacement persistReplacementOperation(
            AgentCheckpoint checkpoint,
            List<ReasoningStep> history,
            String previousOperationId,
            String toolName,
            String paramsJson,
            String role,
            CheckpointStatus checkpointStatus,
            String previousObservation)
            throws Exception {
        Map<String, Object> params = parseParams(paramsJson);
        String operationId = ToolOperationIdentity.generateCanonicalUuid();
        OptionalInt previousPending = PendingToolOperationIdLookup.findUniquePendingIndex(history, previousOperationId);
        if (previousPending.isPresent()) {
            int index = previousPending.getAsInt();
            history.set(index, history.get(index).withDiagnostics(previousObservation, false, null, null, null));
        }
        history.add(ReasoningStep.pending("Prepared pending tool action.", toolName, params, 0, 0)
                .withOperationId(operationId));
        checkpoint.setPendingToolName(toolName);
        checkpoint.setPendingToolRole(role);
        checkpoint.setPendingToolParametersJson(paramsJson);
        checkpoint.setPreExecutedResults(null);
        checkpoint.setBatchToolDecisions(null);
        checkpoint.setStatus(checkpointStatus);
        agentStore.saveWithSteps(checkpoint, history);
        return new Replacement(operationId, params);
    }

    Replacement persistReplacementOperation(
            PendingToolResumeExecutor.PendingToolRequest request,
            String toolName,
            String paramsJson,
            CheckpointStatus checkpointStatus,
            String previousObservation)
            throws Exception {
        return persistReplacementOperation(
                request.checkpoint(),
                request.history(),
                request.operationId(),
                toolName,
                paramsJson,
                request.effectiveRole(),
                checkpointStatus,
                previousObservation);
    }

    boolean persistDelegatedWait(
            AgentCheckpoint checkpoint,
            String sessionId,
            String originalToolName,
            String role,
            String paramsJson,
            List<ReasoningStep> history) {
        agentStore.clearBatchResuming(sessionId);
        checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
        checkpoint.setPendingToolName(originalToolName);
        checkpoint.setPendingToolRole(role);
        checkpoint.setPendingToolParametersJson(paramsJson);
        checkpoint.setPreExecutedResults(null);
        try {
            checkpoint.setBatchToolDecisions(
                    objectMapper.writeValueAsString(Map.of(originalToolName, ToolDecision.APPROVED)));
            agentStore.saveWithSteps(checkpoint, history);
            return true;
        } catch (Exception e) {
            log.warnf("[DELEGATION] Could not persist parent delegated wait %s: %s", sessionId, LogUtil.rootMessage(e));
            return false;
        }
    }

    AgentResult handleResumeExecutionError(PendingToolResumeExecutor.PendingToolRequest request, Exception exception) {
        AgentCheckpoint checkpoint = request.checkpoint();
        checkpoint.setStatus(CheckpointStatus.FAILED);
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        agentStore.save(checkpoint);

        String message = AgentErrorMessageUtil.safeMessage(exception);
        log.errorf(
                LogUtil.relevantThrowable(exception),
                "Resume tool execution failed session=%s tool=%s: %s",
                request.sessionId(),
                request.toolName(),
                LogUtil.rootMessage(exception));
        return AgentResult.failed(
                "TOOL_EXECUTION_FAILED: " + message,
                AgentResult.FailureCategory.SYSTEM_ERROR,
                List.copyOf(request.history()));
    }

    void completeInProgressCheckpoint(AgentCheckpoint checkpoint) {
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
        agentStore.save(checkpoint);
    }

    void recordReasoningStep(
            String tool,
            String paramsJson,
            String output,
            boolean success,
            String errorCode,
            String operationId,
            List<ReasoningStep> history,
            String sessionId) {
        OptionalInt pendingIndex = (operationId != null)
                ? PendingToolOperationIdLookup.findUniquePendingIndex(history, operationId)
                : OptionalInt.empty();

        final ReasoningStep completed;
        if (pendingIndex.isPresent()) {
            ReasoningStep pending = history.get(pendingIndex.getAsInt());
            completed = new ReasoningStep.ToolCallStep(
                            pending.thought(),
                            pending.action(),
                            pending.actionInput(),
                            output,
                            pending.inputTokens(),
                            pending.outputTokens(),
                            success,
                            errorCode,
                            pending.traceId(),
                            pending.rootCauseClass(),
                            pending.isFastPath(),
                            pending.isTruncationRecovery())
                    .withOperationId(operationId);
            history.set(pendingIndex.getAsInt(), completed);
        } else {
            Map<String, Object> params = parsePausedParams(paramsJson);
            ReasoningStep pending = ReasoningStep.pending(
                    "Executing " + tool + " after human approval.", tool, params != null ? params : Map.of(), 0, 0);
            if (operationId != null) {
                pending = pending.withOperationId(operationId);
            }
            completed = pending.withDiagnostics(output, success, errorCode, null, null);
            history.add(completed);
        }
    }

    record Replacement(String operationId, Map<String, Object> params) {}
}
