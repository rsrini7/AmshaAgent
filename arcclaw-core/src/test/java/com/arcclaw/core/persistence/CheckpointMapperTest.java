package com.arcclaw.core.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.domain.model.AgentCheckpointRecord;
import com.arcclaw.core.domain.model.CheckpointRecord;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.persistence.api.CheckpointDto;
import org.junit.jupiter.api.Test;

class CheckpointMapperTest {

    @Test
    void testToDto() {
        AgentCheckpoint entity = new AgentCheckpointRecord();
        entity.setId(1L);
        entity.setSessionId("s1");
        entity.setAgentRole("coordinator");
        entity.setOriginalGoal("goal");
        entity.setSystemNote("note");
        entity.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        entity.setPendingToolName(ToolCapability.GITHUB.toolName());
        entity.setPendingToolParametersJson("{\"arg\":\"val\"}");
        entity.setBatchToolDecisions("{\"" + ToolCapability.GITHUB.toolName() + "\":\"PENDING\"}");
        entity.setStateJson("[{\"thought\":\"thinking\",\"action\":\"" + ToolCapability.GITHUB.toolName() + "\"}]");
        entity.setMaxIterations(5);
        entity.setChannelType("telegram");
        entity.setChannelPlatformId("u123");
        entity.setPreExecutedResults("{\"tool\":\"result\"}");
        entity.setErrorCode("ERR123");
        entity.setTraceId("trace-456");
        entity.setRootCauseClass("java.lang.RuntimeException");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setFailedAt(Instant.now().plusSeconds(1));
        entity.setCompletedAt(Instant.now().plusSeconds(2));

        CheckpointDto dto = CheckpointMapper.toDto(entity);
        
        assertEquals(entity.getId(), dto.id());
        assertEquals(entity.getSessionId(), dto.sessionId());
        assertEquals(entity.getAgentRole(), dto.agentRole());
        assertEquals(entity.getOriginalGoal(), dto.originalGoal());
        assertEquals(entity.getSystemNote(), dto.systemNote());
        assertEquals(entity.getStatus().name(), dto.status());
        assertEquals(entity.getPendingToolName(), dto.pendingToolName());
        assertEquals(entity.getPendingToolParametersJson(), dto.pendingToolParametersJson());
        assertEquals(entity.getBatchToolDecisions(), dto.batchToolDecisions());
        assertEquals(entity.getStateJson(), dto.stateJson());
        assertEquals(entity.getMaxIterations(), dto.maxIterations());
        assertEquals(entity.getChannelType(), dto.channelType());
        assertEquals(entity.getChannelPlatformId(), dto.channelPlatformId());
        assertEquals(entity.getPreExecutedResults(), dto.preExecutedResults());
        assertEquals(entity.getErrorCode(), dto.errorCode());
        assertEquals(entity.getTraceId(), dto.traceId());
        assertEquals(entity.getRootCauseClass(), dto.rootCauseClass());
        assertEquals(entity.getCreatedAt(), dto.createdAt());
        assertEquals(entity.getUpdatedAt(), dto.updatedAt());
        assertEquals(entity.getFailedAt(), dto.failedAt());
        assertEquals(entity.getCompletedAt(), dto.completedAt());
    }

    @Test
    void testFromDtoRoundTrip() {
        AgentCheckpointRecord source = new AgentCheckpointRecord();
        source.setId(1L);
        source.setSessionId("s1");
        source.setAgentRole("coordinator");
        source.setOriginalGoal("goal");
        source.setSystemNote("note");
        source.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        source.setPendingToolName(ToolCapability.GITHUB.toolName());
        source.setPendingToolParametersJson("{\"arg\":\"val\"}");
        source.setBatchToolDecisions("{\"" + ToolCapability.GITHUB.toolName() + "\":\"PENDING\"}");
        source.setStateJson("[{\"thought\":\"thinking\",\"action\":\"" + ToolCapability.GITHUB.toolName() + "\"}]");
        source.setMaxIterations(5);
        source.setChannelType("telegram");
        source.setChannelPlatformId("u123");
        source.setPreExecutedResults("{\"tool\":\"result\"}");
        source.setErrorCode("ERR123");
        source.setTraceId("trace-456");
        source.setRootCauseClass("java.lang.RuntimeException");
        source.setCreatedAt(Instant.now());
        source.setUpdatedAt(Instant.now());
        source.setFailedAt(Instant.now().plusSeconds(1));
        source.setCompletedAt(Instant.now().plusSeconds(2));

        CheckpointDto dto = CheckpointMapper.toDto(source);
        AgentCheckpoint record = CheckpointRecord.fromDto(dto);

        assertEquals(source.getId(), record.getId());
        assertEquals(source.getSessionId(), record.getSessionId());
        assertEquals(source.getAgentRole(), record.getAgentRole());
        assertEquals(source.getOriginalGoal(), record.getOriginalGoal());
        assertEquals(source.getSystemNote(), record.getSystemNote());
        assertEquals(source.getStatus(), record.getStatus());
        assertEquals(source.getStateJson(), record.getStateJson());
        assertEquals(source.getPendingToolName(), record.getPendingToolName());
        assertEquals(source.getPendingToolParametersJson(), record.getPendingToolParametersJson());
        assertEquals(source.getBatchToolDecisions(), record.getBatchToolDecisions());
        assertEquals(source.getMaxIterations(), record.getMaxIterations());
        assertEquals(source.getChannelType(), record.getChannelType());
        assertEquals(source.getChannelPlatformId(), record.getChannelPlatformId());
        assertEquals(source.getPreExecutedResults(), record.getPreExecutedResults());
        assertEquals(source.getErrorCode(), record.getErrorCode());
        assertEquals(source.getTraceId(), record.getTraceId());
        assertEquals(source.getRootCauseClass(), record.getRootCauseClass());
        assertEquals(source.getCreatedAt(), record.getCreatedAt());
        assertEquals(source.getUpdatedAt(), record.getUpdatedAt());
        assertEquals(source.getFailedAt(), record.getFailedAt());
        assertEquals(source.getCompletedAt(), record.getCompletedAt());
        assertEquals(source.getIterationsUsed(), record.getIterationsUsed());
        assertEquals(source.getRetryAfterMs(), record.getRetryAfterMs());
        assertEquals(source.getAutonomyLevel(), record.getAutonomyLevel());
    }

    @Test
    void testFromDto_nullTimestampsSafe() {
        CheckpointDto dto = new CheckpointDto(
            null, "s1", "coord", "AWAITING_APPROVAL", "FULL", "[]",
            "u123", "telegram", "goal", null, null, null,
            "tool", "{}", "{}", null, 5, 0, null, "note", null,
            "ERR", "trace", "java.lang.RuntimeException", null, 0, null, null
        );

        AgentCheckpoint record = CheckpointRecord.fromDto(dto);
        assertEquals("s1", record.getSessionId());
        assertNull(record.getCreatedAt());
        assertNull(record.getUpdatedAt());
    }
}
