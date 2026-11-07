package com.amsha.core.agent.loop;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amsha.channel.api.MediaAttachment;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.trace.StationCode;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.pipeline.TurnDispatchService;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;

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

    static AgentExecutionContext createExecutionContext(
            String sessionId,
            String capturedUserId,
            String effectiveRole,
            String goal,
            String systemNote,
            int maxIterations,
            MessageContext context,
            CheckpointResumeCoordinator.ResumeDecision decision,
            StructuredOutputMode mode,
            StatefulAgentStore agentStore,
            IntentClassificationService intentClassificationService) {

        Map<String, Object> mergedData = new HashMap<>();
        if (context != null) {
            mergedData.putAll(context.getData());
        }
        if (decision instanceof CheckpointResumeCoordinator.ResumeDecision.ResumedHistory r
                && r.contextDataJson() != null) {
            Map<String, Object> restored = agentStore.deserializeContextData(r.contextDataJson());
            if (restored != null) {
                mergedData.putAll(restored);
            }
        }
        if (TurnDispatchService.hasInboundAudio(context)) {
            mergedData.put(TurnDispatchService.DATA_INBOUND_AUDIO, true);
        }

        String chatId = context != null && context.getChatId() != null ? context.getChatId() : sessionId;
        AutonomyLevel autonomy = context != null && context.getAutonomyLevel().isPresent()
                ? context.getAutonomyLevel().get()
                : (WorkspaceContext.AUTONOMY.isBound() && WorkspaceContext.AUTONOMY.get() != null
                        ? WorkspaceContext.AUTONOMY.get()
                        : AutonomyLevel.SUPERVISED);
        String channelTypeStr = context != null && context.getChannelType() != null
                ? context.getChannelType().name()
                : WorkspaceContext.channelType().orElse(null);
        String channelPlatformId = context != null && context.getUser() != null
                ? context.getUser().platformId()
                : WorkspaceContext.channelPlatformId();

        int startingIteration = 0;
        if (decision instanceof CheckpointResumeCoordinator.ResumeDecision.ResumedHistory r) {
            if (intentClassificationService.isResumeCommand(goal)
                    || isContinuationRequest(context, intentClassificationService)
                    || (context != null && context.isResumeSignal())) {
                startingIteration = r.iterationsUsed();
            }
        }

        List<MediaAttachment> attachments = (context != null
                        && context.getMessage() != null
                        && context.getMessage().attachments() != null)
                ? context.getMessage().attachments()
                : List.of();

        return new AgentExecutionContext(
                sessionId,
                capturedUserId,
                chatId,
                effectiveRole,
                goal,
                autonomy,
                mode,
                maxIterations,
                startingIteration,
                channelTypeStr,
                channelPlatformId,
                systemNote,
                mergedData,
                attachments);
    }

    private static boolean isContinuationRequest(MessageContext context, IntentClassificationService service) {
        return context != null
                && context.getMessage() != null
                && context.getMessage().text() != null
                && !context.getMessage().text().isBlank()
                && service.isContinuationCommand(context.getMessage().text());
    }
}
