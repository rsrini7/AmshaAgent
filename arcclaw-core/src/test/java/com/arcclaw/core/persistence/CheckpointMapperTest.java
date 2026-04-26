// arcclaw-core/src/test/java/com/arcclaw/core/persistence/CheckpointMapperTest.java

package com.arcclaw.core.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.persistence.api.CheckpointDto;
import org.junit.jupiter.api.Test;

class CheckpointMapperTest {

    @Test
    void roundTrip_allFieldsPreserved() {
        AgentCheckpoint entity = new AgentCheckpoint();
        entity.setId(42L);
        entity.setSessionId("sess-roundtrip");
        entity.setAgentRole("ORCHESTRATOR");
        entity.setStatus(CheckpointStatus.IN_PROGRESS);
        entity.setAutonomyLevel(AutonomyLevel.SUPERVISED);
        entity.setStateJson("{\"steps\":[]}");
        entity.setChannelPlatformId("user-123");
        entity.setChannelType("websocket");
        entity.setOriginalGoal("Do the thing");
        entity.setFailedAt(null);
        entity.setCompletedAt(null);
        entity.setFailReason(null);
        entity.setPendingToolName("bash");
        entity.setBatchToolDecisions("{\"bash\":\"PENDING\"}");
        entity.setPendingToolParametersJson("{\"cmd\":\"ls\"}");
        entity.setPreExecutedResults("{\"bash\":\"ok\"}");
        entity.setMaxIterations(10);
        entity.setIterationsUsed(3);
        entity.setRetryAfterMs(500);
        entity.setSystemNote("note");
        entity.setErrorCode("ERR_001");
        entity.setTraceId("trace-abc");
        entity.setRootCauseClass("SomeException");
        entity.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2025-01-02T00:00:00Z"));

        CheckpointDto dto = CheckpointMapper.toDto(entity);
        AgentCheckpoint roundTripped = CheckpointMapper.toEntity(dto);

        assertEquals(entity.getId(),                  roundTripped.getId());
        assertEquals(entity.getSessionId(),            roundTripped.getSessionId());
        assertEquals(entity.getAgentRole(),            roundTripped.getAgentRole());
        assertEquals(entity.getStatus(),               roundTripped.getStatus());
        assertEquals(entity.getAutonomyLevel(),        roundTripped.getAutonomyLevel());
        assertEquals(entity.getStateJson(),            roundTripped.getStateJson());
        assertEquals(entity.getChannelPlatformId(),    roundTripped.getChannelPlatformId());
        assertEquals(entity.getChannelType(),          roundTripped.getChannelType());
        assertEquals(entity.getOriginalGoal(),         roundTripped.getOriginalGoal());
        assertEquals(entity.getPendingToolName(),      roundTripped.getPendingToolName());
        assertEquals(entity.getBatchToolDecisions(),   roundTripped.getBatchToolDecisions());
        assertEquals(entity.getPendingToolParametersJson(), roundTripped.getPendingToolParametersJson());
        assertEquals(entity.getPreExecutedResults(),   roundTripped.getPreExecutedResults());
        assertEquals(entity.getMaxIterations(),        roundTripped.getMaxIterations());
        assertEquals(entity.getIterationsUsed(),       roundTripped.getIterationsUsed());
        assertEquals(entity.getRetryAfterMs(),         roundTripped.getRetryAfterMs());
        assertEquals(entity.getSystemNote(),           roundTripped.getSystemNote());
        assertEquals(entity.getErrorCode(),            roundTripped.getErrorCode());
        assertEquals(entity.getTraceId(),              roundTripped.getTraceId());
        assertEquals(entity.getRootCauseClass(),       roundTripped.getRootCauseClass());
        assertEquals(entity.getCreatedAt(),            roundTripped.getCreatedAt());
        assertEquals(entity.getUpdatedAt(),            roundTripped.getUpdatedAt());
    }

    @Test
    void roundTrip_nullFieldsSafe() {
        AgentCheckpoint sparse = new AgentCheckpoint();
        sparse.setId(1L);
        sparse.setSessionId("sess-sparse");
        // All other fields null — verifies no NPE in mapper
        assertDoesNotThrow(() -> CheckpointMapper.toDto(sparse));
    }

    @Test
    void toEntity_nullTimestampsSafe() {
        CheckpointDto dto = new CheckpointDto(
            1L, "sess-1", "COORDINATOR", "IN_PROGRESS", "SUPERVISED", "{}", "user-1", "telegram",
            "goal", null, null, null, null, null, null, null,
            5, 0, null, null, null, null, null, Instant.now(), Instant.now()
        );
        AgentCheckpoint entity = assertDoesNotThrow(() -> CheckpointMapper.toEntity(dto));
        assertNull(entity.getFailedAt());
        assertNull(entity.getCompletedAt());
    }
}
