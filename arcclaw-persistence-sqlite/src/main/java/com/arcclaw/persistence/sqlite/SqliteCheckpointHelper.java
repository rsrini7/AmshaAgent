package com.arcclaw.persistence.sqlite;

import java.util.List;
import java.util.Map;

import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.ReasoningStepDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jdbi.v3.core.Handle;

import io.quarkus.logging.Log;

/**
 * Helper methods extracted from {@link SqliteCheckpointStoreAdapter} to keep it under 500 lines.
 */
final class SqliteCheckpointHelper {

    private SqliteCheckpointHelper() {}

    static void insertSteps(Handle handle, Long checkpointId, List<ReasoningStepDto> steps) {
        var batch = handle.prepareBatch(
                "INSERT INTO reasoning_step (checkpoint_id, sequence_num, thoughtJson, action, actionInputJson, observation, input_tokens, output_tokens, success, error_code, trace_id, root_cause_class) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        for (int i = 0; i < steps.size(); i++) {
            ReasoningStepDto s = steps.get(i);
            batch.add(
                    checkpointId,
                    i,
                    s.thoughtJson(),
                    s.action(),
                    s.actionInputJson(),
                    s.observation(),
                    s.inputTokens(),
                    s.outputTokens(),
                    s.success(),
                    s.errorCode(),
                    s.traceId(),
                    s.rootCauseClass());
        }
        batch.execute();
    }

    static CheckpointDto mergeBatchDecisions(CheckpointDto managed, CheckpointDto incoming, ObjectMapper objectMapper) {
        if (incoming.batchToolDecisions() == null) {
            return managed.batchToolDecisions() != null
                    ? incoming.withBatchToolDecisions(managed.batchToolDecisions())
                    : incoming;
        }
        if (managed.batchToolDecisions() == null) return incoming;
        try {
            Map<String, ToolDecision> managedMap =
                    objectMapper.readValue(managed.batchToolDecisions(), new TypeReference<>() {});
            Map<String, ToolDecision> incomingMap =
                    objectMapper.readValue(incoming.batchToolDecisions(), new TypeReference<>() {});
            boolean changed = false;
            for (var entry : managedMap.entrySet()) {
                if (ToolDecision.PENDING != entry.getValue()
                        && ToolDecision.PENDING == incomingMap.get(entry.getKey())) {
                    incomingMap.put(entry.getKey(), entry.getValue());
                    changed = true;
                }
            }
            if (changed) {
                try {
                    return incoming.withBatchToolDecisions(objectMapper.writeValueAsString(incomingMap));
                } catch (Exception e) {
                    Log.warnf("Failed to serialize merged decisions: %s", e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.warnf("Failed to merge batch decisions for session %s: %s", managed.sessionId(), e.getMessage());
        }
        return incoming;
    }
}
