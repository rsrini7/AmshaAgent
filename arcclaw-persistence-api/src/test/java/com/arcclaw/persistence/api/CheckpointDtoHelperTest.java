package com.arcclaw.persistence.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Verifies CheckpointDtoHelper preserves all fields correctly after extraction
 * from inline constructors. Each method must map 1:1 from the original DTO.
 */
class CheckpointDtoHelperTest {

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String FAILED = "FAILED";
    private static final String COMPLETED = "COMPLETED";

    private static CheckpointDto sample() {
        Instant now = Instant.now();
        return new CheckpointDto(
                1L, "session-1", "coder", IN_PROGRESS, "SUPERVISED",
                "{}", "tg", "TELEGRAM", "build a calculator",
                null, null, null, "sandbox_exec", "{}", "{}",
                null, 50, 3, 1000, "system", "{}",
                "ERR", "trace-1", "NullPointerException",
                null, 0, now, now);
    }

    @Test
    void withStatus_preservesAllFields() {
        CheckpointDto cp = sample();
        CheckpointDto result = CheckpointDtoHelper.withStatus(cp, FAILED);

        assertEquals(cp.id(), result.id());
        assertEquals(cp.sessionId(), result.sessionId());
        assertEquals(cp.agentRole(), result.agentRole());
        assertEquals(FAILED, result.status());
        assertEquals(cp.autonomyLevel(), result.autonomyLevel());
        assertEquals(cp.stateJson(), result.stateJson());
        assertEquals(cp.channelPlatformId(), result.channelPlatformId());
        assertEquals(cp.channelType(), result.channelType());
        assertEquals(cp.originalGoal(), result.originalGoal());
        assertEquals(cp.failedAt(), result.failedAt());
        assertEquals(cp.completedAt(), result.completedAt());
        assertEquals(cp.failReason(), result.failReason());
        assertEquals(cp.pendingToolName(), result.pendingToolName());
        assertEquals(cp.batchToolDecisions(), result.batchToolDecisions());
        assertEquals(cp.pendingToolParametersJson(), result.pendingToolParametersJson());
        assertEquals(cp.preExecutedResults(), result.preExecutedResults());
        assertEquals(cp.maxIterations(), result.maxIterations());
        assertEquals(cp.iterationsUsed(), result.iterationsUsed());
        assertEquals(cp.retryAfterMs(), result.retryAfterMs());
        assertEquals(cp.systemNote(), result.systemNote());
        assertEquals(cp.contextDataJson(), result.contextDataJson());
        assertEquals(cp.errorCode(), result.errorCode());
        assertEquals(cp.traceId(), result.traceId());
        assertEquals(cp.rootCauseClass(), result.rootCauseClass());
        assertEquals(cp.parentCheckpointId(), result.parentCheckpointId());
        assertEquals(cp.depth(), result.depth());
        assertEquals(cp.createdAt(), result.createdAt());
        assertNotNull(result.updatedAt());
    }

    @Test
    void withFailure_setsFailedAtAndReason() {
        CheckpointDto cp = sample();
        Instant failedAt = Instant.now();
        CheckpointDto result = CheckpointDtoHelper.withFailure(cp, FAILED, failedAt, "OOM", "E001", "tr-1", "OutOfMemoryError");

        assertEquals(FAILED, result.status());
        assertEquals(failedAt, result.failedAt());
        assertEquals("OOM", result.failReason());
        assertEquals("E001", result.errorCode());
        assertEquals("tr-1", result.traceId());
        assertEquals("OutOfMemoryError", result.rootCauseClass());
        assertEquals(cp.sessionId(), result.sessionId());
        assertEquals(cp.stateJson(), result.stateJson());
    }

    @Test
    void withCompletion_setsCompletedAt() {
        CheckpointDto cp = sample();
        Instant completedAt = Instant.now();
        CheckpointDto result = CheckpointDtoHelper.withCompletion(cp, COMPLETED, completedAt);

        assertEquals(COMPLETED, result.status());
        assertEquals(completedAt, result.completedAt());
        assertEquals(cp.sessionId(), result.sessionId());
    }

    @Test
    void withRetry_clearsFailFields() {
        CheckpointDto cp = sample();
        CheckpointDto result = CheckpointDtoHelper.withRetry(cp, IN_PROGRESS);

        assertEquals(IN_PROGRESS, result.status());
        assertNull(result.failedAt());
        assertNull(result.failReason());
        assertNull(result.errorCode());
        assertNull(result.traceId());
        assertNull(result.rootCauseClass());
        assertEquals(cp.sessionId(), result.sessionId());
        assertEquals(cp.batchToolDecisions(), result.batchToolDecisions());
    }

    @Test
    void create_setsMinimalFields() {
        CheckpointDto result = CheckpointDtoHelper.create("sess", "coder", IN_PROGRESS, "SUPERVISED", "{}", "goal", 5, "{}");

        assertNull(result.id());
        assertEquals("sess", result.sessionId());
        assertEquals("coder", result.agentRole());
        assertEquals(IN_PROGRESS, result.status());
        assertEquals("SUPERVISED", result.autonomyLevel());
        assertEquals("goal", result.originalGoal());
        assertEquals(5, result.iterationsUsed());
        assertEquals("{}", result.contextDataJson());
        assertNotNull(result.createdAt());
        assertNotNull(result.updatedAt());
        assertNull(result.channelPlatformId());
        assertNull(result.errorCode());
        assertEquals(0, result.depth());
    }
}
