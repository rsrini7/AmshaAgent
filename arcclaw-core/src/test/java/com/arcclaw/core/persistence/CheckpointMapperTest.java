package com.arcclaw.core.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.persistence.api.CheckpointDto;
import org.junit.jupiter.api.Test;

class CheckpointMapperTest {

    @Test
    void testRoundTrip() {
        AgentCheckpoint entity = new AgentCheckpoint();
        entity.setId(1L);
        entity.setSessionId("s1");
        entity.setAgentRole("coordinator");
        entity.setOriginalGoal("goal");
        entity.setSystemNote("note");
        entity.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        entity.setPendingToolName("github");
        entity.setPendingToolParametersJson("{\"arg\":\"val\"}");
        entity.setBatchToolDecisions("{\"github\":\"PENDING\"}");
        entity.setStateJson("[{\"thought\":\"thinking\",\"action\":\"github\"}]");
        entity.setMaxIterations(5);
        entity.setChannelType("telegram");
        entity.setChannelPlatformId("u123");
        entity.setPreExecutedResults("{\"tool\":\"result\"}");
        entity.setErrorCode("ERR123");
        entity.setTraceId("trace-456");
        entity.setRootCauseClass("java.lang.RuntimeException");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

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

        AgentCheckpoint backToEntity = CheckpointMapper.toEntity(dto);
        
        assertEquals(entity.getId(), backToEntity.getId());
        assertEquals(entity.getSessionId(), backToEntity.getSessionId());
        assertEquals(entity.getAgentRole(), backToEntity.getAgentRole());
        assertEquals(entity.getOriginalGoal(), backToEntity.getOriginalGoal());
        assertEquals(entity.getStatus(), backToEntity.getStatus());
    }

    @Test
    void testToEntity_nullTimestampsSafe() {
        CheckpointDto dto = new CheckpointDto(
            null, "s1", "coord", "AWAITING_APPROVAL", "FULL", "[]",
            "u123", "telegram", "goal", null, null, null,
            "tool", "{}", "{}", null, 5, 0, null, "note",
            "ERR", "trace", "java.lang.RuntimeException", null, null
        );

        AgentCheckpoint entity = CheckpointMapper.toEntity(dto);
        assertEquals("s1", entity.getSessionId());
        assertNull(entity.getCreatedAt());
        assertNull(entity.getUpdatedAt());
    }
}
