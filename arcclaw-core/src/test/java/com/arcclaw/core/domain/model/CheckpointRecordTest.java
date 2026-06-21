package com.arcclaw.core.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.persistence.api.CheckpointDto;

import org.junit.jupiter.api.Test;

/**
 * Deep unit tests for {@link CheckpointRecord}.
 * Tests all getters/setters, fromDto, hasBatchToolDecisions, isParallelBatch,
 * parallelToolNames, hasBrowserTool, hasPreExecutedResults.
 */
class CheckpointRecordTest {

    // -----------------------------------------------------------------------
    // fromDto
    // -----------------------------------------------------------------------

    @Test
    void fromDto_mapsAllFields() {
        Instant now = Instant.now();
        CheckpointDto dto = new CheckpointDto(
                42L,
                "sess-1",
                "coder",
                "IN_PROGRESS",
                "SUPERVISED",
                "{\"key\":\"val\"}",
                "tg-123",
                "TELEGRAM",
                "build app",
                null,
                null,
                null,
                "sandbox_exec",
                "{}",
                "{\"code\":\"1+1\"}",
                null,
                50,
                5,
                2000,
                "note",
                "{\"ctx\":true}",
                "ERR_001",
                "trace-abc",
                "NullPointerException",
                100L,
                2,
                now,
                now);

        CheckpointRecord r = CheckpointRecord.fromDto(dto);

        assertEquals(42L, r.getId());
        assertEquals("sess-1", r.getSessionId());
        assertEquals("coder", r.getAgentRole());
        assertEquals(CheckpointStatus.IN_PROGRESS, r.getStatus());
        assertEquals(AutonomyLevel.SUPERVISED, r.getAutonomyLevel());
        assertEquals("{\"key\":\"val\"}", r.getStateJson());
        assertEquals("tg-123", r.getChannelPlatformId());
        assertEquals("TELEGRAM", r.getChannelType());
        assertEquals("build app", r.getOriginalGoal());
        assertNull(r.getFailedAt());
        assertNull(r.getCompletedAt());
        assertNull(r.getFailReason());
        assertEquals("sandbox_exec", r.getPendingToolName());
        assertEquals("{}", r.getBatchToolDecisions());
        assertEquals("{\"code\":\"1+1\"}", r.getPendingToolParametersJson());
        assertNull(r.getPreExecutedResults());
        assertEquals(50, r.getMaxIterations());
        assertEquals(5, r.getIterationsUsed());
        assertEquals(2000, r.getRetryAfterMs());
        assertEquals("note", r.getSystemNote());
        assertEquals("{\"ctx\":true}", r.getContextDataJson());
        assertEquals("ERR_001", r.getErrorCode());
        assertEquals("trace-abc", r.getTraceId());
        assertEquals("NullPointerException", r.getRootCauseClass());
        assertEquals(100L, r.getParentCheckpointId());
        assertEquals(2, r.getDepth());
        assertEquals(now, r.getCreatedAt());
        assertEquals(now, r.getUpdatedAt());
    }

    @Test
    void fromDto_nullStatusAndAutonomy() {
        CheckpointDto dto = new CheckpointDto(
                null, "sess", "role", null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, 0, null, null, null, null, null, null, null, 0, null, null);

        CheckpointRecord r = CheckpointRecord.fromDto(dto);
        assertNull(r.getStatus());
        assertNull(r.getAutonomyLevel());
    }

    // -----------------------------------------------------------------------
    // Getters / Setters
    // -----------------------------------------------------------------------

    @Test
    void setAndGetId() {
        CheckpointRecord r = new CheckpointRecord();
        r.setId(99L);
        assertEquals(99L, r.getId());
    }

    @Test
    void setAndGetVersion() {
        CheckpointRecord r = new CheckpointRecord();
        r.setVersion(3);
        assertEquals(3, r.getVersion());
    }

    @Test
    void setAndGetSessionId() {
        CheckpointRecord r = new CheckpointRecord();
        r.setSessionId("abc");
        assertEquals("abc", r.getSessionId());
    }

    @Test
    void setAndGetAgentRole() {
        CheckpointRecord r = new CheckpointRecord();
        r.setAgentRole("brahma");
        assertEquals("brahma", r.getAgentRole());
    }

    @Test
    void setAndGetStatus() {
        CheckpointRecord r = new CheckpointRecord();
        r.setStatus(CheckpointStatus.FAILED);
        assertEquals(CheckpointStatus.FAILED, r.getStatus());
    }

    @Test
    void setAndGetAutonomyLevel() {
        CheckpointRecord r = new CheckpointRecord();
        r.setAutonomyLevel(AutonomyLevel.FULL);
        assertEquals(AutonomyLevel.FULL, r.getAutonomyLevel());
    }

    @Test
    void setAndGetStateJson() {
        CheckpointRecord r = new CheckpointRecord();
        r.setStateJson("{\"x\":1}");
        assertEquals("{\"x\":1}", r.getStateJson());
    }

    @Test
    void setAndGetChannelPlatformId() {
        CheckpointRecord r = new CheckpointRecord();
        r.setChannelPlatformId("tg-456");
        assertEquals("tg-456", r.getChannelPlatformId());
    }

    @Test
    void setAndGetChannelType() {
        CheckpointRecord r = new CheckpointRecord();
        r.setChannelType("CLI");
        assertEquals("CLI", r.getChannelType());
    }

    @Test
    void setAndGetOriginalGoal() {
        CheckpointRecord r = new CheckpointRecord();
        r.setOriginalGoal("do something");
        assertEquals("do something", r.getOriginalGoal());
    }

    @Test
    void setAndGetFailedAt() {
        CheckpointRecord r = new CheckpointRecord();
        Instant t = Instant.now();
        r.setFailedAt(t);
        assertEquals(t, r.getFailedAt());
    }

    @Test
    void setAndGetCompletedAt() {
        CheckpointRecord r = new CheckpointRecord();
        Instant t = Instant.now();
        r.setCompletedAt(t);
        assertEquals(t, r.getCompletedAt());
    }

    @Test
    void setAndGetFailReason() {
        CheckpointRecord r = new CheckpointRecord();
        r.setFailReason("OOM");
        assertEquals("OOM", r.getFailReason());
    }

    @Test
    void setAndGetPendingToolName() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPendingToolName("sandbox_exec");
        assertEquals("sandbox_exec", r.getPendingToolName());
    }

    @Test
    void setAndGetPendingToolRole() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPendingToolRole("coder");
        assertEquals("coder", r.getPendingToolRole());
    }

    @Test
    void setAndGetBatchToolDecisions() {
        CheckpointRecord r = new CheckpointRecord();
        r.setBatchToolDecisions("{\"decisions\":[]}");
        assertEquals("{\"decisions\":[]}", r.getBatchToolDecisions());
    }

    @Test
    void setAndGetPendingToolParametersJson() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPendingToolParametersJson("{\"code\":\"1+1\"}");
        assertEquals("{\"code\":\"1+1\"}", r.getPendingToolParametersJson());
    }

    @Test
    void setAndGetPreExecutedResults() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPreExecutedResults("result data");
        assertEquals("result data", r.getPreExecutedResults());
    }

    @Test
    void setAndGetMaxIterations() {
        CheckpointRecord r = new CheckpointRecord();
        r.setMaxIterations(100);
        assertEquals(100, r.getMaxIterations());
    }

    @Test
    void setAndGetIterationsUsed() {
        CheckpointRecord r = new CheckpointRecord();
        r.setIterationsUsed(42);
        assertEquals(42, r.getIterationsUsed());
    }

    @Test
    void setAndGetRetryAfterMs() {
        CheckpointRecord r = new CheckpointRecord();
        r.setRetryAfterMs(5000);
        assertEquals(5000, r.getRetryAfterMs());
    }

    @Test
    void setAndGetSystemNote() {
        CheckpointRecord r = new CheckpointRecord();
        r.setSystemNote("important note");
        assertEquals("important note", r.getSystemNote());
    }

    @Test
    void setAndGetContextDataJson() {
        CheckpointRecord r = new CheckpointRecord();
        r.setContextDataJson("{\"key\":\"val\"}");
        assertEquals("{\"key\":\"val\"}", r.getContextDataJson());
    }

    @Test
    void setAndGetErrorCode() {
        CheckpointRecord r = new CheckpointRecord();
        r.setErrorCode("E001");
        assertEquals("E001", r.getErrorCode());
    }

    @Test
    void setAndGetTraceId() {
        CheckpointRecord r = new CheckpointRecord();
        r.setTraceId("trace-xyz");
        assertEquals("trace-xyz", r.getTraceId());
    }

    @Test
    void setAndGetRootCauseClass() {
        CheckpointRecord r = new CheckpointRecord();
        r.setRootCauseClass("IllegalStateException");
        assertEquals("IllegalStateException", r.getRootCauseClass());
    }

    @Test
    void setAndGetParentCheckpointId() {
        CheckpointRecord r = new CheckpointRecord();
        r.setParentCheckpointId(500L);
        assertEquals(500L, r.getParentCheckpointId());
    }

    @Test
    void setAndGetDepth() {
        CheckpointRecord r = new CheckpointRecord();
        r.setDepth(3);
        assertEquals(3, r.getDepth());
    }

    @Test
    void setAndGetCreatedAt() {
        CheckpointRecord r = new CheckpointRecord();
        Instant t = Instant.now();
        r.setCreatedAt(t);
        assertEquals(t, r.getCreatedAt());
    }

    @Test
    void setAndGetUpdatedAt() {
        CheckpointRecord r = new CheckpointRecord();
        Instant t = Instant.now();
        r.setUpdatedAt(t);
        assertEquals(t, r.getUpdatedAt());
    }

    // -----------------------------------------------------------------------
    // hasBatchToolDecisions
    // -----------------------------------------------------------------------

    @Test
    void hasBatchToolDecisions_null_returnsFalse() {
        CheckpointRecord r = new CheckpointRecord();
        assertFalse(r.hasBatchToolDecisions());
    }

    @Test
    void hasBatchToolDecisions_blank_returnsFalse() {
        CheckpointRecord r = new CheckpointRecord();
        r.setBatchToolDecisions("   ");
        assertFalse(r.hasBatchToolDecisions());
    }

    @Test
    void hasBatchToolDecisions_nonBlank_returnsTrue() {
        CheckpointRecord r = new CheckpointRecord();
        r.setBatchToolDecisions("{\"decisions\":[]}");
        assertTrue(r.hasBatchToolDecisions());
    }

    // -----------------------------------------------------------------------
    // isParallelBatch
    // -----------------------------------------------------------------------

    @Test
    void isParallelBatch_null_returnsFalse() {
        CheckpointRecord r = new CheckpointRecord();
        assertFalse(r.isParallelBatch());
    }

    @Test
    void isParallelBatch_regularTool_returnsFalse() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPendingToolName("sandbox_exec");
        assertFalse(r.isParallelBatch());
    }

    @Test
    void isParallelBatch_parallelPrefix_returnsTrue() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPendingToolName(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX + "sandbox_exec,github");
        assertTrue(r.isParallelBatch());
    }

    // -----------------------------------------------------------------------
    // parallelToolNames
    // -----------------------------------------------------------------------

    @Test
    void parallelToolNames_notParallel_returnsEmpty() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPendingToolName("sandbox_exec");
        List<String> names = r.parallelToolNames();
        assertTrue(names.isEmpty());
    }

    @Test
    void parallelToolNames_nullPendingTool_returnsEmpty() {
        CheckpointRecord r = new CheckpointRecord();
        assertTrue(r.parallelToolNames().isEmpty());
    }

    @Test
    void parallelToolNames_parallel_returnsToolNames() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPendingToolName(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX + "sandbox_exec,github,serper_web_search");
        List<String> names = r.parallelToolNames();
        assertEquals(3, names.size());
        assertEquals("sandbox_exec", names.get(0));
        assertEquals("github", names.get(1));
        assertEquals("serper_web_search", names.get(2));
    }

    @Test
    void parallelToolNames_singleParallel_returnsSingleElement() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPendingToolName(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX + "sandbox_exec");
        List<String> names = r.parallelToolNames();
        assertEquals(1, names.size());
        assertEquals("sandbox_exec", names.get(0));
    }

    // -----------------------------------------------------------------------
    // hasBrowserTool
    // -----------------------------------------------------------------------

    @Test
    void hasBrowserTool_nullPendingTool_returnsFalse() {
        CheckpointRecord r = new CheckpointRecord();
        assertFalse(r.hasBrowserTool());
    }

    @Test
    void hasBrowserTool_sandboxExec_returnsFalse() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPendingToolName("sandbox_exec");
        assertFalse(r.hasBrowserTool());
    }

    @Test
    void hasBrowserTool_github_returnsFalse() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPendingToolName("github");
        assertFalse(r.hasBrowserTool());
    }

    @Test
    void hasBrowserTool_unknownTool_returnsFalse() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPendingToolName("nonexistent_tool_xyz");
        assertFalse(r.hasBrowserTool());
    }

    @Test
    void hasBrowserTool_parallelWithBrowser_returnsTrue() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPendingToolName(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX + "sandbox_exec,browser_navigate,github");
        assertTrue(r.hasBrowserTool());
    }

    @Test
    void hasBrowserTool_parallelWithoutBrowser_returnsFalse() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPendingToolName(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX + "sandbox_exec,github");
        assertFalse(r.hasBrowserTool());
    }

    @Test
    void hasBrowserTool_parallelWithSerper_returnsTrue() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPendingToolName(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX + "serper_web_search");
        assertTrue(r.hasBrowserTool());
    }

    // -----------------------------------------------------------------------
    // hasPreExecutedResults
    // -----------------------------------------------------------------------

    @Test
    void hasPreExecutedResults_null_returnsFalse() {
        CheckpointRecord r = new CheckpointRecord();
        assertFalse(r.hasPreExecutedResults());
    }

    @Test
    void hasPreExecutedResults_blank_returnsFalse() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPreExecutedResults("   ");
        assertFalse(r.hasPreExecutedResults());
    }

    @Test
    void hasPreExecutedResults_nonBlank_returnsTrue() {
        CheckpointRecord r = new CheckpointRecord();
        r.setPreExecutedResults("{\"result\":\"ok\"}");
        assertTrue(r.hasPreExecutedResults());
    }
}
