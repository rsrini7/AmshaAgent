package com.amsha.persistence.postgres;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.common.util.LogUtil;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.ToolDecision;
import com.amsha.persistence.postgres.jdbi.CheckpointJdbiDao;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Handle;

import io.quarkus.logging.Log;

/**
 * Helper methods extracted from {@link PostgresCheckpointStoreAdapter} to keep it under 500 lines.
 */
final class PostgresCheckpointHelper {

    private PostgresCheckpointHelper() {}

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
                    Log.warnf("Failed to serialize merged decisions: %s", LogUtil.rootMessage(e));
                }
            }
        } catch (Exception e) {
            Log.warnf(
                    "Failed to merge batch decisions for session %s: %s", managed.sessionId(), LogUtil.rootMessage(e));
        }
        return incoming;
    }

    static boolean updateBatchToolDecision(
            CheckpointJdbiDao dao,
            String sessionId,
            String agentRole,
            String toolName,
            String decision,
            ObjectMapper objectMapper,
            Logger log) {
        Optional<CheckpointDto> cp = dao.findAnyBySessionAndRole(sessionId, agentRole);
        if (cp.isEmpty()) {
            log.warnf("[BatchOps] No checkpoint found for session=%s", sessionId);
            return false;
        }
        CheckpointDto dto = cp.get();
        if (!CheckpointStatus.AWAITING_APPROVAL.name().equals(dto.status())) {
            log.warnf(
                    "[BatchOps] Checkpoint status is '%s' instead of AWAITING_APPROVAL for session=%s",
                    dto.status(), sessionId);
            return false;
        }
        String rawJson = dto.batchToolDecisions();
        if (rawJson == null || rawJson.isBlank()) {
            log.warnf("[BatchOps] batchToolDecisions is null for session=%s", sessionId);
            return false;
        }
        try {
            Map<String, ToolDecision> map =
                    objectMapper.readValue(rawJson, new TypeReference<Map<String, ToolDecision>>() {});
            if (!map.containsKey(toolName)) {
                log.warnf("[BatchOps] Tool '%s' not in batch for session %s.", toolName, sessionId);
                return false;
            }
            if (map.get(toolName) != ToolDecision.PENDING) {
                log.infof("[BatchOps] Tool '%s' is already decided for session=%s", toolName, sessionId);
                return false;
            }
            map.put(toolName, ToolDecision.valueOf(decision));
            String updatedJson = objectMapper.writeValueAsString(map);
            return dao.atomicUpdateBatchDecision(sessionId, agentRole, updatedJson, rawJson, Instant.now());
        } catch (Exception e) {
            log.errorf("[BatchOps] Failed to update batch tool decision: %s", LogUtil.rootMessage(e));
            return false;
        }
    }
}
