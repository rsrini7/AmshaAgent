package com.amsha.core.agent.loop;

import java.util.List;
import java.util.Map;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ToolDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Routes checkpoint resumption to the appropriate resume strategy.
 *
 * <p>Pending-tool execution is kept in {@link PendingToolResumeExecutor} so this
 * class only owns checkpoint routing, goal isolation, and denial handling.</p>
 */
class ResumeStrategyDispatcher {
    private static final Logger log = Logger.getLogger(ResumeStrategyDispatcher.class);

    private final StatefulAgentStore agentStore;
    private final ObjectMapper objectMapper;
    private final ParallelBatchResumeHandler parallelBatchHandler;
    private final PendingToolResumeExecutor pendingToolExecutor;

    ResumeStrategyDispatcher(
            StatefulAgentStore agentStore,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ReasoningEngine reasoningEngine,
            ExecutionFailureRecoveryService recoveryService,
            PersonaEscalationService escalationService) {
        this(
                agentStore,
                toolRegistry,
                objectMapper,
                reasoningEngine,
                recoveryService,
                escalationService,
                new ToolDelegationResolver(),
                null);
    }

    ResumeStrategyDispatcher(
            StatefulAgentStore agentStore,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ReasoningEngine reasoningEngine,
            ExecutionFailureRecoveryService recoveryService,
            PersonaEscalationService escalationService,
            ToolDelegationResolver delegationResolver,
            ToolInvocationGuard guard) {
        this.agentStore = agentStore;
        this.objectMapper = objectMapper;
        this.parallelBatchHandler =
                new ParallelBatchResumeHandler(agentStore, toolRegistry, objectMapper, delegationResolver, guard);
        this.pendingToolExecutor = new PendingToolResumeExecutor(
                agentStore,
                toolRegistry,
                objectMapper,
                reasoningEngine,
                recoveryService,
                escalationService,
                delegationResolver,
                guard);
    }

    AgentResult handle(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            MessageContext messageContext,
            AgentStateMachineRunner stateMachineRunner,
            AgentCheckpoint checkpoint) {
        if (AgentSentinels.GOAL_DENY_APPROVED.equals(context.goal())
                || CheckpointStatus.DENIED == checkpoint.getStatus()) {
            return performDenialCleanup(context, history, checkpoint);
        }

        if (checkpoint.hasBatchToolDecisions()) {
            try {
                Map<String, ToolDecision> decisions = objectMapper.readValue(
                        checkpoint.getBatchToolDecisions(), new TypeReference<Map<String, ToolDecision>>() {});
                boolean allDenied =
                        !decisions.isEmpty() && decisions.values().stream().allMatch(ToolDecision.DENIED::equals);
                if (allDenied) {
                    return performDenialCleanup(context, history, checkpoint);
                }
            } catch (Exception e) {
                log.warnf(
                        "Failed to parse batch decisions during denial check for session %s: %s",
                        context.sessionId(), LogUtil.rootMessage(e));
                return performDenialCleanup(context, history, checkpoint);
            }
        }

        String messageGoal = messageContext == null ? null : messageContext.getGoal();
        String resumeGoal = messageGoal != null
                        && !messageGoal.isBlank()
                        && !AgentSentinels.GOAL_RESUME_APPROVED.equals(messageGoal)
                ? messageGoal
                : resolveResumeGoal(checkpoint);
        AgentExecutionContext finalContext = isolateResumeGoal(context, resumeGoal);
        String channelType = resolveChannelType(finalContext, messageContext);

        if (checkpoint.isParallelBatch()) {
            return parallelBatchHandler.resumeParallelBatch(finalContext, history, checkpoint, channelType);
        }
        return pendingToolExecutor.execute(finalContext, history, checkpoint, channelType, stateMachineRunner);
    }

    private AgentExecutionContext isolateResumeGoal(AgentExecutionContext context, String resumeGoal) {
        if (context.goal().equals(resumeGoal)) {
            return context;
        }
        log.infof(
                "[ResumeStrategy] Isolated specific resumption goal for session %s: %s",
                context.sessionId(), resumeGoal);
        return new AgentExecutionContext(
                context.sessionId(),
                context.userId(),
                context.chatId(),
                context.agentRole(),
                resumeGoal,
                context.autonomyLevel(),
                context.structuredOutputMode(),
                context.maxIterations(),
                context.startingIteration(),
                context.channelType(),
                context.channelPlatformId(),
                context.systemNote(),
                context.data());
    }

    String resolveResumeGoal(String sessionId, String agentRole) {
        return agentStore
                .load(sessionId, agentRole)
                .map(this::resolveResumeGoal)
                .orElse(AgentSentinels.GOAL_RESUME_APPROVED);
    }

    String resolveResumeGoal(AgentCheckpoint checkpoint) {
        if (checkpoint.hasBatchToolDecisions()) {
            try {
                Map<String, ToolDecision> decisions = objectMapper.readValue(
                        checkpoint.getBatchToolDecisions(), new TypeReference<Map<String, ToolDecision>>() {});
                boolean allDenied =
                        !decisions.isEmpty() && decisions.values().stream().allMatch(ToolDecision.DENIED::equals);
                if (allDenied) {
                    return AgentSentinels.GOAL_DENY_APPROVED;
                }
            } catch (Exception _) {
                log.warnf(
                        "resolveResumeGoal: failed to parse batch decisions for session %s", checkpoint.getSessionId());
            }
        }
        String originalGoal = checkpoint.getOriginalGoal();
        return originalGoal != null && !originalGoal.isBlank() ? originalGoal : AgentSentinels.GOAL_RESUME_APPROVED;
    }

    private AgentResult performDenialCleanup(
            AgentExecutionContext context, List<ReasoningStep> history, AgentCheckpoint checkpoint) {
        log.infof("[ResumeStrategy] User denied tool execution for session %s", context.sessionId());
        checkpoint.setStatus(CheckpointStatus.FAILED);
        checkpoint.setFailReason("User denied tool execution.");
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        agentStore.save(checkpoint);
        return AgentResult.finished(
                "❌ Tool execution was denied and the task has been cancelled.", List.copyOf(history));
    }

    private String resolveChannelType(AgentExecutionContext context, MessageContext messageContext) {
        if (messageContext != null
                && messageContext.getUser() != null
                && messageContext.getUser().channel() != null
                && messageContext.getUser().channel().id() != null
                && !messageContext.getUser().channel().id().isBlank()) {
            return messageContext.getUser().channel().id();
        }
        if (context != null
                && context.channelType() != null
                && !context.channelType().isBlank()) {
            return context.channelType();
        }
        return "cli";
    }
}
