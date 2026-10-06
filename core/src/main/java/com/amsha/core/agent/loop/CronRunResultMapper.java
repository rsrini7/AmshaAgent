package com.amsha.core.agent.loop;

import java.time.Instant;
import java.util.Objects;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.persistence.api.CronRunDto;

/** Maps agent outcomes onto the persisted cron-run record. */
public final class CronRunResultMapper {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_AWAITING_APPROVAL = "AWAITING_APPROVAL";
    public static final String STATUS_AWAITING_RETRY = "AWAITING_RETRY";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_LOST = "LOST";

    private CronRunResultMapper() {}

    public static CronRunDto fromResult(CronRunDto run, AgentResult result) {
        Objects.requireNonNull(result, "result");
        String status =
                switch (result) {
                    case AgentResult.Finished _, AgentResult.StructuredFinished _ -> STATUS_COMPLETED;
                    case AgentResult.AwaitingApproval _ -> STATUS_AWAITING_APPROVAL;
                    case AgentResult.AwaitingRetry _ -> STATUS_AWAITING_RETRY;
                    case AgentResult.Failed _, AgentResult.LoopExceeded _ -> STATUS_FAILED;
                };
        String errorReason =
                switch (result) {
                    case AgentResult.Failed failed -> failed.reason();
                    case AgentResult.LoopExceeded _ -> "Loop exceeded";
                    default -> null;
                };
        return withStatus(run, status, errorReason, result);
    }

    public static CronRunDto withStatus(CronRunDto run, String status, String errorReason, AgentResult result) {
        Objects.requireNonNull(run, "run");
        int inputTokens = result == null ? 0 : result.totalInputTokens();
        int outputTokens = result == null ? 0 : result.totalOutputTokens();
        return new CronRunDto(
                run.id(),
                run.cronId(),
                run.sessionId(),
                status,
                run.startedAt(),
                Instant.now(),
                errorReason,
                run.iterationsUsed(),
                inputTokens,
                outputTokens,
                run.usdCost(),
                run.createdAt());
    }
}
