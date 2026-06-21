package com.amsha.core.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.persistence.api.CheckpointDto;

import org.junit.jupiter.api.Test;

/**
 * Deep coverage tests for the {@link AgentCheckpoint} interface default methods (withers).
 * Uses {@link CheckpointRecord} as the concrete implementation.
 */
class AgentCheckpointInterfaceTest {

    // -----------------------------------------------------------------------
    // Wither methods — fluent mutation returning this
    // -----------------------------------------------------------------------

    @Test
    void withId_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        AgentCheckpoint result = r.withId(42L);
        assertSame(r, result, "Wither should return same instance");
        assertEquals(42L, r.getId());
    }

    @Test
    void withVersion_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        AgentCheckpoint result = r.withVersion(5);
        assertSame(r, result);
        assertEquals(5, r.getVersion());
    }

    @Test
    void withSessionId_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withSessionId("sess-abc");
        assertEquals("sess-abc", r.getSessionId());
    }

    @Test
    void withAgentRole_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withAgentRole("garuda");
        assertEquals("garuda", r.getAgentRole());
    }

    @Test
    void withStatus_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withStatus(CheckpointStatus.IN_PROGRESS);
        assertEquals(CheckpointStatus.IN_PROGRESS, r.getStatus());
    }

    @Test
    void withAutonomyLevel_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withAutonomyLevel(AutonomyLevel.BACKGROUND);
        assertEquals(AutonomyLevel.BACKGROUND, r.getAutonomyLevel());
    }

    @Test
    void withStateJson_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withStateJson("{\"state\":1}");
        assertEquals("{\"state\":1}", r.getStateJson());
    }

    @Test
    void withChannelPlatformId_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withChannelPlatformId("cli-1");
        assertEquals("cli-1", r.getChannelPlatformId());
    }

    @Test
    void withChannelType_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withChannelType("CLI");
        assertEquals("CLI", r.getChannelType());
    }

    @Test
    void withOriginalGoal_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withOriginalGoal("test goal");
        assertEquals("test goal", r.getOriginalGoal());
    }

    @Test
    void withFailedAt_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        Instant t = Instant.now();
        r.withFailedAt(t);
        assertEquals(t, r.getFailedAt());
    }

    @Test
    void withCompletedAt_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        Instant t = Instant.now();
        r.withCompletedAt(t);
        assertEquals(t, r.getCompletedAt());
    }

    @Test
    void withFailReason_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withFailReason("OOM");
        assertEquals("OOM", r.getFailReason());
    }

    @Test
    void withPendingToolName_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withPendingToolName("sandbox_exec");
        assertEquals("sandbox_exec", r.getPendingToolName());
    }

    @Test
    void withPendingToolRole_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withPendingToolRole("coder");
        assertEquals("coder", r.getPendingToolRole());
    }

    @Test
    void withBatchToolDecisions_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withBatchToolDecisions("{\"d\":1}");
        assertEquals("{\"d\":1}", r.getBatchToolDecisions());
    }

    @Test
    void withPendingToolParametersJson_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withPendingToolParametersJson("{\"p\":1}");
        assertEquals("{\"p\":1}", r.getPendingToolParametersJson());
    }

    @Test
    void withPreExecutedResults_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withPreExecutedResults("result");
        assertEquals("result", r.getPreExecutedResults());
    }

    @Test
    void withMaxIterations_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withMaxIterations(100);
        assertEquals(100, r.getMaxIterations());
    }

    @Test
    void withIterationsUsed_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withIterationsUsed(10);
        assertEquals(10, r.getIterationsUsed());
    }

    @Test
    void withRetryAfterMs_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withRetryAfterMs(5000);
        assertEquals(5000, r.getRetryAfterMs());
    }

    @Test
    void withSystemNote_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withSystemNote("note");
        assertEquals("note", r.getSystemNote());
    }

    @Test
    void withContextDataJson_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withContextDataJson("{\"c\":1}");
        assertEquals("{\"c\":1}", r.getContextDataJson());
    }

    @Test
    void withErrorCode_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withErrorCode("E001");
        assertEquals("E001", r.getErrorCode());
    }

    @Test
    void withTraceId_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withTraceId("trace-1");
        assertEquals("trace-1", r.getTraceId());
    }

    @Test
    void withRootCauseClass_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withRootCauseClass("NPE");
        assertEquals("NPE", r.getRootCauseClass());
    }

    @Test
    void withCreatedAt_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        Instant t = Instant.now();
        r.withCreatedAt(t);
        assertEquals(t, r.getCreatedAt());
    }

    @Test
    void withUpdatedAt_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        Instant t = Instant.now();
        r.withUpdatedAt(t);
        assertEquals(t, r.getUpdatedAt());
    }

    @Test
    void withParentCheckpointId_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withParentCheckpointId(999L);
        assertEquals(999L, r.getParentCheckpointId());
    }

    @Test
    void withDepth_setsAndReturnsSelf() {
        CheckpointRecord r = new CheckpointRecord();
        r.withDepth(7);
        assertEquals(7, r.getDepth());
    }

    // -----------------------------------------------------------------------
    // Fluent chaining — multiple withers in sequence
    // -----------------------------------------------------------------------

    @Test
    void fluentChaining_multipleWithers() {
        CheckpointRecord r = new CheckpointRecord();
        Instant now = Instant.now();
        AgentCheckpoint result = r.withId(1L)
                .withSessionId("s1")
                .withAgentRole("coder")
                .withStatus(CheckpointStatus.IN_PROGRESS)
                .withAutonomyLevel(AutonomyLevel.FULL)
                .withOriginalGoal("test")
                .withDepth(2)
                .withCreatedAt(now);

        assertSame(r, result);
        assertEquals(1L, r.getId());
        assertEquals("s1", r.getSessionId());
        assertEquals("coder", r.getAgentRole());
        assertEquals(CheckpointStatus.IN_PROGRESS, r.getStatus());
        assertEquals(AutonomyLevel.FULL, r.getAutonomyLevel());
        assertEquals("test", r.getOriginalGoal());
        assertEquals(2, r.getDepth());
        assertEquals(now, r.getCreatedAt());
    }

    // -----------------------------------------------------------------------
    // fromDto — full round-trip via interface
    // -----------------------------------------------------------------------

    @Test
    void fromDto_thenWither_modifiesCorrectly() {
        CheckpointDto dto = new CheckpointDto(
                1L,
                "s1",
                "coder",
                "IN_PROGRESS",
                "SUPERVISED",
                "{}",
                null,
                null,
                "goal",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                50,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null);

        CheckpointRecord r = CheckpointRecord.fromDto(dto);
        r.withStatus(CheckpointStatus.COMPLETED).withCompletedAt(Instant.now());

        assertEquals(CheckpointStatus.COMPLETED, r.getStatus());
        assertNotNull(r.getCompletedAt());
        assertEquals("s1", r.getSessionId());
    }
}
