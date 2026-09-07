package com.amsha.core.agent.loop;

import com.amsha.core.agent.trace.StationCode;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;

/**
 * Package-private helper routines for trace publishing and role classification in {@link AgentLoop}.
 * Extracted to preserve strict NCSS and class-size PMD gates.
 */
final class AgentLoopTraceHelper {

    private AgentLoopTraceHelper() {}

    static void publishTrace(
            TracePublisher tracePublisher,
            TraceStage stage,
            String sessionId,
            StationCode station,
            String status,
            String detail,
            String paradigm) {
        if (tracePublisher == null || sessionId == null) {
            return;
        }
        tracePublisher.publish(TracePublisher.newEvent(sessionId, stage, paradigm)
                .withStation(station)
                .withStatus(status)
                .withDetail(detail));
    }

    static void publishPreflightTrace(
            TracePublisher tracePublisher,
            String sessionId,
            CheckpointResumeCoordinator.ResumeDecision decision,
            String paradigm) {
        String preflightStatus =
                switch (decision) {
                    case CheckpointResumeCoordinator.ResumeDecision.AbortedStale _ -> "ABORTED_STALE";
                    case CheckpointResumeCoordinator.ResumeDecision.ResumedHistory _ -> "RESUMED";
                    case CheckpointResumeCoordinator.ResumeDecision.FreshStart _ -> "PASS";
                };
        String preflightDetail =
                switch (decision) {
                    case CheckpointResumeCoordinator.ResumeDecision.AbortedStale _ -> "stale_approval_discarded";
                    case CheckpointResumeCoordinator.ResumeDecision.ResumedHistory r ->
                        "resumed_checkpoint_role=" + r.checkpointRole();
                    case CheckpointResumeCoordinator.ResumeDecision.FreshStart _ -> "fresh_goal";
                };
        publishTrace(
                tracePublisher,
                TraceStage.PREFLIGHT,
                sessionId,
                StationCode.STATION_02_PREFLIGHT,
                preflightStatus,
                preflightDetail,
                paradigm);
    }

    static boolean isMultiAgentRole(String role) {
        if (role == null) {
            return false;
        }
        String upper = role.toUpperCase();
        return upper.contains("COORDINATOR") || upper.contains("GROUP_CONSULTATION");
    }
}
