package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.state.CheckpointSerializer;
import com.amsha.core.tool.attempt.ToolOperationIdentity;
import com.amsha.persistence.api.CheckpointDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Matches a startup attempt to a checkpoint using its persisted operation identity. */
@ApplicationScoped
public class StartupCheckpointOperationMatcher {

    private static final TypeReference<Map<String, Object>> PARAMETERS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<ParallelToolCall>> BATCH_CALLS_TYPE = new TypeReference<>() {};

    private final CheckpointSerializer checkpointSerializer;
    private final ObjectMapper objectMapper;

    @Inject
    public StartupCheckpointOperationMatcher(CheckpointSerializer checkpointSerializer, ObjectMapper objectMapper) {
        this.checkpointSerializer = checkpointSerializer;
        this.objectMapper = objectMapper;
    }

    public boolean matches(CheckpointDto checkpoint, String operationId, String toolName) {
        if (checkpoint == null || operationId == null || toolName == null || toolName.isBlank()) {
            return false;
        }
        try {
            ToolOperationIdentity.validateCanonicalUuid(operationId, "operationId");
        } catch (IllegalArgumentException _) {
            return false;
        }

        String pendingToolName = checkpoint.pendingToolName();
        if (pendingToolName == null || pendingToolName.isBlank()) {
            return false;
        }
        if (pendingToolName.startsWith(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX)) {
            return matchesParallelCall(checkpoint, operationId, toolName, pendingToolName);
        }
        return matchesSequentialCall(checkpoint, operationId, toolName, pendingToolName);
    }

    private boolean matchesSequentialCall(
            CheckpointDto checkpoint, String operationId, String toolName, String pendingToolName) {
        if (!pendingToolName.equals(toolName)) {
            return false;
        }
        Optional<Map<String, Object>> parameters = parseParameters(checkpoint.pendingToolParametersJson());
        if (parameters.isEmpty()) {
            return false;
        }
        List<ReasoningStep> history = checkpointSerializer.deserializeSteps(checkpoint.id(), checkpoint.stateJson());
        return PendingToolOperationIdLookup.find(history, toolName, parameters.get())
                .filter(operationId::equals)
                .isPresent();
    }

    private boolean matchesParallelCall(
            CheckpointDto checkpoint, String operationId, String toolName, String pendingToolName) {
        if (!declaresParallelTool(pendingToolName, toolName)) {
            return false;
        }
        String callsJson = checkpoint.pendingToolParametersJson();
        if (callsJson == null || callsJson.isBlank()) {
            return false;
        }
        List<ParallelToolCall> calls;
        try {
            calls = objectMapper.readValue(callsJson, BATCH_CALLS_TYPE);
        } catch (JsonProcessingException _) {
            return false;
        }
        if (calls == null) {
            return false;
        }
        List<ParallelToolCall> matchingCalls = calls.stream()
                .filter(Objects::nonNull)
                .filter(call -> operationId.equals(call.operationId()))
                .toList();
        return matchingCalls.size() == 1
                && toolName.equals(matchingCalls.getFirst().toolName());
    }

    private boolean declaresParallelTool(String pendingToolName, String toolName) {
        String names = pendingToolName.substring(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX.length());
        for (String name : names.split("[|,]", -1)) {
            if (name.equals(toolName)) {
                return true;
            }
        }
        return false;
    }

    private Optional<Map<String, Object>> parseParameters(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> parameters = objectMapper.readValue(json, PARAMETERS_TYPE);
            return Optional.ofNullable(parameters);
        } catch (JsonProcessingException _) {
            return Optional.empty();
        }
    }
}
