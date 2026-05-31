package com.arcclaw.persistence.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Deep coverage tests for {@link CheckpointDtoHelper}.
 * Supplements the existing CheckpointDtoHelperTest with coverage for
 * withBatchDecisions, withChannelInfo, and withOrphanReason.
 * Also tests edge cases in all helper methods.
 */
class CheckpointDtoHelperDeepTest {

    private static CheckpointDto fullSample() {
        Instant now = Instant.now();
        return new CheckpointDto(
                1L,
                "session-1",
                "coder",
                "IN_PROGRESS",
                "SUPERVISED",
                "{\"key\":\"val\"}",
                "tg-123",
                "TELEGRAM",
                "build a calculator",
                null,
                null,
                null,
                "sandbox_exec",
                "{\"old\":\"decisions\"}",
                "{\"code\":\"1+1\"}",
                "pre-result",
                50,
                3,
                1000,
                "system note",
                "{\"ctx\":true}",
                "ERR",
                "trace-1",
                "NullPointerException",
                100L,
                2,
                now,
                now);
    }

    // -----------------------------------------------------------------------
    // withBatchDecisions
    // -----------------------------------------------------------------------

    @Test
    void withBatchDecisions_updatesDecisionsOnly() {
        CheckpointDto cp = fullSample();
        CheckpointDto result = CheckpointDtoHelper.withBatchDecisions(cp, "{\"new\":\"decisions\"}");

        assertEquals("{\"new\":\"decisions\"}", result.batchToolDecisions());
        // All other fields preserved
        assertEquals(cp.id(), result.id());
        assertEquals(cp.sessionId(), result.sessionId());
        assertEquals(cp.agentRole(), result.agentRole());
        assertEquals(cp.status(), result.status());
        assertEquals(cp.autonomyLevel(), result.autonomyLevel());
        assertEquals(cp.stateJson(), result.stateJson());
        assertEquals(cp.channelPlatformId(), result.channelPlatformId());
        assertEquals(cp.channelType(), result.channelType());
        assertEquals(cp.originalGoal(), result.originalGoal());
        assertEquals(cp.failedAt(), result.failedAt());
        assertEquals(cp.completedAt(), result.completedAt());
        assertEquals(cp.failReason(), result.failReason());
        assertEquals(cp.pendingToolName(), result.pendingToolName());
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
    void withBatchDecisions_nullDecisions() {
        CheckpointDto cp = fullSample();
        CheckpointDto result = CheckpointDtoHelper.withBatchDecisions(cp, null);
        assertNull(result.batchToolDecisions());
    }

    // -----------------------------------------------------------------------
    // withChannelInfo
    // -----------------------------------------------------------------------

    @Test
    void withChannelInfo_updatesChannelFields() {
        CheckpointDto cp = fullSample();
        CheckpointDto result = CheckpointDtoHelper.withChannelInfo(cp, "CLI", "cli-session-1");

        assertEquals("CLI", result.channelType());
        assertEquals("cli-session-1", result.channelPlatformId());
        // All other fields preserved
        assertEquals(cp.id(), result.id());
        assertEquals(cp.sessionId(), result.sessionId());
        assertEquals(cp.agentRole(), result.agentRole());
        assertEquals(cp.status(), result.status());
        assertEquals(cp.autonomyLevel(), result.autonomyLevel());
        assertEquals(cp.stateJson(), result.stateJson());
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
    void withChannelInfo_nullValues() {
        CheckpointDto cp = fullSample();
        CheckpointDto result = CheckpointDtoHelper.withChannelInfo(cp, null, null);
        assertNull(result.channelType());
        assertNull(result.channelPlatformId());
    }

    // -----------------------------------------------------------------------
    // withOrphanReason
    // -----------------------------------------------------------------------

    @Test
    void withOrphanReason_setsOrphanedStatusAndReason() {
        CheckpointDto cp = fullSample();
        CheckpointDto result = CheckpointDtoHelper.withOrphanReason(cp, "scheduler outage");

        assertEquals("ORPHANED", result.status());
        assertEquals("scheduler outage", result.failReason());
        // All other fields preserved
        assertEquals(cp.id(), result.id());
        assertEquals(cp.sessionId(), result.sessionId());
        assertEquals(cp.agentRole(), result.agentRole());
        assertEquals(cp.autonomyLevel(), result.autonomyLevel());
        assertEquals(cp.stateJson(), result.stateJson());
        assertEquals(cp.channelPlatformId(), result.channelPlatformId());
        assertEquals(cp.channelType(), result.channelType());
        assertEquals(cp.originalGoal(), result.originalGoal());
        assertEquals(cp.failedAt(), result.failedAt());
        assertEquals(cp.completedAt(), result.completedAt());
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
    void withOrphanReason_nullReason() {
        CheckpointDto cp = fullSample();
        CheckpointDto result = CheckpointDtoHelper.withOrphanReason(cp, null);
        assertEquals("ORPHANED", result.status());
        assertNull(result.failReason());
    }

    // -----------------------------------------------------------------------
    // withStatus — edge cases
    // -----------------------------------------------------------------------

    @Test
    void withStatus_updatesStatusAndUpdatedAt() {
        CheckpointDto cp = fullSample();
        CheckpointDto result = CheckpointDtoHelper.withStatus(cp, "COMPLETED");

        assertEquals("COMPLETED", result.status());
        assertNotNull(result.updatedAt());
        // createdAt preserved
        assertEquals(cp.createdAt(), result.createdAt());
    }

    @Test
    void withStatus_nullStatus() {
        CheckpointDto cp = fullSample();
        CheckpointDto result = CheckpointDtoHelper.withStatus(cp, null);
        assertNull(result.status());
    }

    // -----------------------------------------------------------------------
    // withFailure — edge cases
    // -----------------------------------------------------------------------

    @Test
    void withFailure_nullErrorFields() {
        CheckpointDto cp = fullSample();
        CheckpointDto result = CheckpointDtoHelper.withFailure(cp, "FAILED", Instant.now(), null, null, null, null);

        assertEquals("FAILED", result.status());
        assertNull(result.failReason());
        assertNull(result.errorCode());
        assertNull(result.traceId());
        assertNull(result.rootCauseClass());
    }

    // -----------------------------------------------------------------------
    // withCompletion — edge cases
    // -----------------------------------------------------------------------

    @Test
    void withCompletion_nullCompletedAt() {
        CheckpointDto cp = fullSample();
        CheckpointDto result = CheckpointDtoHelper.withCompletion(cp, "COMPLETED", null);
        assertEquals("COMPLETED", result.status());
        assertNull(result.completedAt());
    }

    // -----------------------------------------------------------------------
    // withRetry — verifies all fail fields are cleared
    // -----------------------------------------------------------------------

    @Test
    void withRetry_clearsAllFailFields() {
        // Start with a fully failed checkpoint
        Instant now = Instant.now();
        CheckpointDto failed = new CheckpointDto(
                1L,
                "s1",
                "coder",
                "FAILED",
                "SUPERVISED",
                "{}",
                "tg",
                "TELEGRAM",
                "goal",
                now,
                null,
                "crash",
                "sandbox_exec",
                "{}",
                "{}",
                null,
                50,
                10,
                2000,
                "note",
                "{}",
                "E001",
                "trace",
                "OOME",
                null,
                0,
                now,
                now);

        CheckpointDto result = CheckpointDtoHelper.withRetry(failed, "IN_PROGRESS");

        assertEquals("IN_PROGRESS", result.status());
        assertNull(result.failedAt(), "failedAt should be cleared");
        assertNull(result.failReason(), "failReason should be cleared");
        assertNull(result.errorCode(), "errorCode should be cleared");
        assertNull(result.traceId(), "traceId should be cleared");
        assertNull(result.rootCauseClass(), "rootCauseClass should be cleared");
        // Non-fail fields preserved
        assertEquals("s1", result.sessionId());
        assertEquals("sandbox_exec", result.pendingToolName());
        assertEquals("{}", result.batchToolDecisions());
        assertEquals(10, result.iterationsUsed());
        assertNotNull(result.updatedAt());
    }

    // -----------------------------------------------------------------------
    // create — verifies all null/default fields
    // -----------------------------------------------------------------------

    @Test
    void create_allFieldsCorrect() {
        CheckpointDto result = CheckpointDtoHelper.create(
                "sess-1", "brahma", "IN_PROGRESS", "FULL", "{\"state\":1}", "analyze data", 7, "{\"ctx\":1}");

        assertNull(result.id());
        assertEquals("sess-1", result.sessionId());
        assertEquals("brahma", result.agentRole());
        assertEquals("IN_PROGRESS", result.status());
        assertEquals("FULL", result.autonomyLevel());
        assertEquals("{\"state\":1}", result.stateJson());
        assertNull(result.channelPlatformId());
        assertNull(result.channelType());
        assertEquals("analyze data", result.originalGoal());
        assertNull(result.failedAt());
        assertNull(result.completedAt());
        assertNull(result.failReason());
        assertNull(result.pendingToolName());
        assertNull(result.batchToolDecisions());
        assertNull(result.pendingToolParametersJson());
        assertNull(result.preExecutedResults());
        assertNull(result.maxIterations());
        assertEquals(7, result.iterationsUsed());
        assertNull(result.retryAfterMs());
        assertNull(result.systemNote());
        assertEquals("{\"ctx\":1}", result.contextDataJson());
        assertNull(result.errorCode());
        assertNull(result.traceId());
        assertNull(result.rootCauseClass());
        assertNull(result.parentCheckpointId());
        assertEquals(0, result.depth());
        assertNotNull(result.createdAt());
        assertNotNull(result.updatedAt());
    }

    // -----------------------------------------------------------------------
    // EnsureTimestamps (from CheckpointDto itself)
    // -----------------------------------------------------------------------

    @Test
    void ensureTimestamps_bothPresent_returnsSame() {
        Instant now = Instant.now();
        CheckpointDto cp = new CheckpointDto(
                1L, "s", "r", "ACTIVE", null, null, null, null, null, null, null, null, null, null, null, null, null, 0,
                null, null, null, null, null, null, null, 0, now, now);

        CheckpointDto result = cp.ensureTimestamps(Instant.EPOCH, Instant.EPOCH);
        assertSame(cp, result, "Should return same instance when timestamps already set");
    }

    @Test
    void ensureTimestamps_nullCreated_usesFallback() {
        Instant now = Instant.now();
        CheckpointDto cp = new CheckpointDto(
                1L, "s", "r", "ACTIVE", null, null, null, null, null, null, null, null, null, null, null, null, null, 0,
                null, null, null, null, null, null, null, 0, null, now);

        Instant fallback = Instant.EPOCH;
        CheckpointDto result = cp.ensureTimestamps(fallback, Instant.EPOCH);
        assertNotSame(cp, result);
        assertEquals(fallback, result.createdAt());
        assertEquals(now, result.updatedAt());
    }

    @Test
    void ensureTimestamps_nullUpdated_usesFallback() {
        Instant now = Instant.now();
        CheckpointDto cp = new CheckpointDto(
                1L, "s", "r", "ACTIVE", null, null, null, null, null, null, null, null, null, null, null, null, null, 0,
                null, null, null, null, null, null, null, 0, now, null);

        Instant fallback = Instant.EPOCH;
        CheckpointDto result = cp.ensureTimestamps(Instant.EPOCH, fallback);
        assertNotSame(cp, result);
        assertEquals(now, result.createdAt());
        assertEquals(fallback, result.updatedAt());
    }

    @Test
    void ensureTimestamps_bothNull_usesBothFallbacks() {
        CheckpointDto cp = new CheckpointDto(
                1L, "s", "r", "ACTIVE", null, null, null, null, null, null, null, null, null, null, null, null, null, 0,
                null, null, null, null, null, null, null, 0, null, null);

        Instant fc = Instant.ofEpochSecond(1000);
        Instant fu = Instant.ofEpochSecond(2000);
        CheckpointDto result = cp.ensureTimestamps(fc, fu);
        assertNotSame(cp, result);
        assertEquals(fc, result.createdAt());
        assertEquals(fu, result.updatedAt());
    }

    // -----------------------------------------------------------------------
    // withBatchToolDecisions (from CheckpointDto itself)
    // -----------------------------------------------------------------------

    @Test
    void checkpointDto_withBatchToolDecisions_updatesField() {
        CheckpointDto cp = fullSample();
        CheckpointDto result = cp.withBatchToolDecisions("{\"updated\":true}");
        assertEquals("{\"updated\":true}", result.batchToolDecisions());
        assertEquals(cp.id(), result.id());
        assertEquals(cp.status(), result.status());
    }
}
