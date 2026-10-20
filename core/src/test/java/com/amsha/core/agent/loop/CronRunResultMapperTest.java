package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.persistence.api.CronRunDto;
import com.amsha.security.api.RiskLevel;

import org.junit.jupiter.api.Test;

class CronRunResultMapperTest {

    private final CronRunDto run = new CronRunDto(
            "run-id",
            "cron-id",
            "cron-session",
            CronRunResultMapper.STATUS_AWAITING_APPROVAL,
            Instant.parse("2026-10-06T08:00:00Z"),
            null,
            null,
            2,
            3,
            4,
            BigDecimal.ZERO,
            Instant.parse("2026-10-06T08:00:00Z"));

    @Test
    void mapsEveryAgentResultVariantToRunStatus() {
        assertMapped(new AgentResult.Finished("done", List.of(), 7, 9), CronRunResultMapper.STATUS_COMPLETED, null);
        assertMapped(
                new AgentResult.StructuredFinished("done", "{}", List.of(), 7, 9),
                CronRunResultMapper.STATUS_COMPLETED,
                null);
        assertMapped(
                new AgentResult.AwaitingApproval(
                        new ApprovalFrame("tool", Map.of(), RiskLevel.MEDIUM), "cron-session", List.of(), 7, 9),
                CronRunResultMapper.STATUS_AWAITING_APPROVAL,
                null);
        assertMapped(
                new AgentResult.AwaitingRetry("cron-session", 1_000, List.of(), 7, 9),
                CronRunResultMapper.STATUS_AWAITING_RETRY,
                null);
        assertMapped(
                new AgentResult.Failed("failed", AgentResult.FailureCategory.SYSTEM_ERROR, List.of(), 7, 9),
                CronRunResultMapper.STATUS_FAILED,
                "failed");
        assertMapped(
                new AgentResult.LoopExceeded(5, List.of(), 7, 9), CronRunResultMapper.STATUS_FAILED, "Loop exceeded");
    }

    private void assertMapped(AgentResult result, String status, String errorReason) {
        CronRunDto updated = CronRunResultMapper.fromResult(run, result);

        assertEquals(status, updated.status());
        assertEquals(errorReason, updated.errorReason());
        assertEquals(run.id(), updated.id());
        assertEquals(run.cronId(), updated.cronId());
        assertEquals(run.sessionId(), updated.sessionId());
        assertEquals(run.startedAt(), updated.startedAt());
        assertEquals(run.createdAt(), updated.createdAt());
        assertEquals(run.usdCost(), updated.usdCost());
        assertEquals(result.totalInputTokens(), updated.inputTokens());
        assertEquals(result.totalOutputTokens(), updated.outputTokens());
    }
}
