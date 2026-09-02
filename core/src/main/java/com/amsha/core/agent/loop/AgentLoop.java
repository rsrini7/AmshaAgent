package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.amsha.channel.api.MediaAttachment;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.agent.trace.StationCode;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.core.audit.AuditHashUtil;
import com.amsha.core.audit.ComplianceViolationException;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.governance.BudgetExceededException;
import com.amsha.core.governance.BudgetWarningException;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.pipeline.TurnDispatchService;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.structured.StructuredOutputException;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.core.util.ClassUtils;
import com.amsha.core.util.MdcManaged;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.memory.tiered.DirectiveFeedbackPort;
import com.amsha.security.api.AutonomyLevel;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.smallrye.common.annotation.Identifier;

/**
 * Lifecycle shell for the Amsha Agent agent reasoning loop.
 */
@ApplicationScoped
@MdcManaged
public class AgentLoop {

    private static final Logger log = Logger.getLogger(AgentLoop.class);
    private static final int DEFAULT_MAX_ITERATIONS = 5;

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    AgentStateMachineRunner stateMachineRunner;

    @Inject
    CheckpointResumeHandler checkpointResumeHandler;

    @Inject
    ChannelPluginRegistry channelPluginRegistry;

    @Inject
    AuditHashUtil hashUtil;

    @Inject
    AgentSseService agentSseService;

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    SessionLifecycleCoordinator sessionLifecycleCoordinator;

    @Inject
    CheckpointResumeCoordinator checkpointResumeCoordinator;

    @Inject
    ObservabilityManager observabilityManager;

    @Inject
    @Identifier("agent-output-mode")
    Instance<StructuredOutputMode> structuredOutputModeInstance;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    IntentClassificationService intentClassificationService;

    @Inject
    ChatMemoryWriter chatMemoryWriter;

    @Inject
    Instance<DirectiveFeedbackPort> directiveFeedbackPortInstance;

    @Inject
    TracePublisher tracePublisher;

    private LoopResultHandler loopResultHandler;

    private LoopResultHandler getLoopResultHandler() {
        var h = this.loopResultHandler;
        if (h == null) {
            h = new LoopResultHandler(
                    agentStore, agentSseService, channelPluginRegistry, intentClassificationService, chatMemoryWriter);
            this.loopResultHandler = h;
        }
        return h;
    }

    @ActivateRequestContext
    public AgentResult resume(String sessionId, String agentRole, String goal) {
        return run(sessionId, agentRole, goal, reasoningEngine, DEFAULT_MAX_ITERATIONS);
    }

    @ActivateRequestContext
    public AgentResult run(String goal, ReasoningEngine engine) {
        return run(goal, engine, DEFAULT_MAX_ITERATIONS);
    }

    @ActivateRequestContext
    public AgentResult run(String sessionId, String agentRole, String goal, ReasoningEngine engine, int maxIterations) {
        int budget = maxIterations;
        if (goal != null) {
            String lower = goal.toLowerCase(Locale.ROOT);
            if (lower.contains("store")
                    || lower.contains("remember")
                    || lower.contains("call me")
                    || lower.contains("my name is")
                    || lower.contains("refer to me as")
                    || lower.contains("address me as")) {
                budget = Math.max(budget, 3);
            }
        }
        return run(sessionId, agentRole, goal, null, engine, budget, null);
    }

    @ActivateRequestContext
    public AgentResult run(
            String sessionId,
            String agentRole,
            String goal,
            ReasoningEngine engine,
            int maxIterations,
            MessageContext context) {
        return run(sessionId, agentRole, goal, null, engine, maxIterations, context);
    }

    @ActivateRequestContext
    public AgentResult run(
            String sessionId,
            String agentRole,
            String goal,
            String systemNote,
            ReasoningEngine engine,
            int maxIterations,
            MessageContext context) {

        String userId = context != null && context.getUser() != null
                ? context.getUser().platformId()
                : WorkspaceContext.getUserIdOrUnknown();
        final String canonicalRole = sessionLifecycleCoordinator.resolveCanonicalRole(sessionId, agentRole, userId);
        return runWithRole(sessionId, canonicalRole, goal, systemNote, engine, maxIterations, context, userId);
    }

    @ActivateRequestContext
    AgentResult runWithRole(
            String sessionId,
            String canonicalRole,
            String goal,
            String systemNote,
            ReasoningEngine engine,
            int maxIterations,
            MessageContext context) {
        return runWithRole(sessionId, canonicalRole, goal, systemNote, engine, maxIterations, context, "unknown");
    }

    @ActivateRequestContext
    AgentResult runWithRole(
            String sessionId,
            String canonicalRole,
            String goal,
            String systemNote,
            ReasoningEngine engine,
            int maxIterations,
            MessageContext context,
            String capturedUserId) {

        sessionLifecycleCoordinator.setupMdc(sessionId, canonicalRole, capturedUserId);
        TracePublisher.startTurn(sessionId);
        String paradigm = AgentLoopTraceHelper.isMultiAgentRole(canonicalRole) ? "MULTI_AGENT" : "REACT";
        publishTrace(TraceStage.TURN_START, sessionId, StationCode.STATION_01_INBOUND, "START", null, paradigm);

        AgentResult result = null;
        boolean exceptionCaught = false;
        List<ReasoningStep> resumedHistory = new ArrayList<>();
        String effectiveGoal = goal;

        Span span = observabilityManager.beginSession(sessionId, capturedUserId, canonicalRole);
        try (Scope _ = span.makeCurrent()) {
            try {
                if (intentClassificationService.isResumeCommand(goal)) {
                    String restoredGoal = agentStore
                            .findLatestNonArchivedCheckpoint(sessionId)
                            .or(() -> agentStore.findLatestCheckpoint(sessionId))
                            .map(AgentCheckpoint::getOriginalGoal)
                            .filter(g -> g != null && !g.isBlank() && !intentClassificationService.isResumeCommand(g))
                            .orElse(null);
                    if (restoredGoal != null && !restoredGoal.isBlank()) {
                        effectiveGoal = restoredGoal;
                        log.infof(
                                "[AgentLoop] Resume command detected for session %s - restoring original goal: %s",
                                sessionId, effectiveGoal);
                    }
                }

                var decision = checkpointResumeCoordinator.resolveResumePath(
                        sessionId, canonicalRole, capturedUserId, context);
                AgentLoopTraceHelper.publishPreflightTrace(tracePublisher, sessionId, decision, paradigm);

                String effectiveRole = canonicalRole;
                if (decision instanceof CheckpointResumeCoordinator.ResumeDecision.ResumedHistory r) {
                    if (intentClassificationService.isResumeCommand(goal) || isContinuationRequest(context)) {
                        resumedHistory.addAll(r.history());
                        effectiveRole = r.checkpointRole();
                        if (isContinuationRequest(context)) {
                            log.infof(
                                    "[AgentLoop] Continuation request detected for session %s - restoring history from %s turn to avoid restarting the original task.",
                                    sessionId, r.checkpointRole());
                        }
                    } else {
                        log.infof(
                                "[AgentLoop] Fresh goal detected for session %s - skipping history restoration from %s turn and resetting to canonical role %s to avoid task confusion.",
                                sessionId, r.checkpointRole(), canonicalRole);
                    }
                }

                publishTrace(
                        TraceStage.ROUTE, sessionId, StationCode.STATION_03_ROUTING, "ROUTED", effectiveRole, paradigm);

                StructuredOutputMode mode =
                        (structuredOutputModeInstance != null && !structuredOutputModeInstance.isUnsatisfied())
                                ? structuredOutputModeInstance.get()
                                : StructuredOutputMode.NATIVE;

                AgentExecutionContext agentExecutionContext = createExecutionContext(
                        sessionId,
                        capturedUserId,
                        effectiveRole,
                        effectiveGoal,
                        systemNote,
                        maxIterations,
                        context,
                        decision,
                        mode);

                result = executeWithinScopes(
                        agentExecutionContext,
                        resumedHistory,
                        context,
                        engine,
                        effectiveGoal,
                        sessionId,
                        canonicalRole,
                        capturedUserId);

                if (result instanceof AgentResult.AwaitingApproval) {
                    getLoopResultHandler().tryStampChannelInfo(sessionId, canonicalRole, context);
                } else if (result instanceof AgentResult.AwaitingRetry awaitingRetry) {
                    getLoopResultHandler().updateStoreForRetry(sessionId, canonicalRole, awaitingRetry, context);
                } else if (result != null
                        && result.steps() != null
                        && !result.steps().isEmpty()) {
                    getLoopResultHandler()
                            .updateStoreForTerminal(
                                    sessionId,
                                    canonicalRole,
                                    capturedUserId,
                                    agentExecutionContext.autonomyLevel(),
                                    result,
                                    effectiveGoal);
                }

                return result;

            } catch (StructuredOutputException e) {
                exceptionCaught = true;
                String msg = AgentErrorMessageUtil.safeMessage(e);
                log.errorf("Structured output failure for session %s: %s", sessionId, msg);
                result = AgentResult.failed(msg, AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(resumedHistory));
                return result;

            } catch (BudgetExceededException e) {
                exceptionCaught = true;
                meterRegistry
                        .counter(
                                "amsha.agent.loop.failure",
                                "role",
                                canonicalRole != null ? canonicalRole : "unknown",
                                "exception",
                                ClassUtils.getCleanName(e))
                        .increment();
                throw e;

            } catch (BudgetWarningException e) {
                exceptionCaught = true;
                throw e;

            } catch (ComplianceViolationException cve) {
                exceptionCaught = true;
                result = getLoopResultHandler().handleComplianceViolation(sessionId, canonicalRole, cve);
                return result;

            } catch (Exception e) {
                exceptionCaught = true;
                meterRegistry
                        .counter(
                                "amsha.agent.loop.failure",
                                "role",
                                canonicalRole != null ? canonicalRole : "unknown",
                                "exception",
                                ClassUtils.getCleanName(e))
                        .increment();
                result = getLoopResultHandler().handleSystemError(sessionId, canonicalRole, e);
                return result;

            } finally {
                boolean isTerminal = (result != null
                                && !(result instanceof AgentResult.AwaitingApproval)
                                && !(result instanceof AgentResult.AwaitingRetry))
                        || exceptionCaught;
                try {
                    if (isTerminal) {
                        boolean success = !exceptionCaught
                                && (result instanceof AgentResult.Finished
                                        || result instanceof AgentResult.StructuredFinished);
                        recordDirectiveOutcome(sessionId, capturedUserId, success);
                    }
                    String goalHash = hashUtil.sha256hex(effectiveGoal);
                    observabilityManager.notifyLoopComplete(sessionId, capturedUserId, canonicalRole, goalHash, result);
                } finally {
                    observabilityManager.endSession(sessionId);
                    publishTrace(
                            TraceStage.TURN_END, sessionId, StationCode.STATION_10_OUTBOUND, "END", null, paradigm);
                    sessionLifecycleCoordinator.teardownMdc();
                    if (isTerminal) {
                        TracePublisher.releaseSession(sessionId);
                    }
                }
            }
        }
    }

    private AgentExecutionContext createExecutionContext(
            String sessionId,
            String capturedUserId,
            String effectiveRole,
            String goal,
            String systemNote,
            int maxIterations,
            MessageContext context,
            CheckpointResumeCoordinator.ResumeDecision decision,
            StructuredOutputMode mode) {

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
            if (intentClassificationService.isResumeCommand(goal) || isContinuationRequest(context)) {
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

    private AgentResult executeWithinScopes(
            AgentExecutionContext agentExecutionContext,
            List<ReasoningStep> resumedHistory,
            MessageContext context,
            ReasoningEngine engine,
            String goal,
            String sessionId,
            String canonicalRole,
            String capturedUserId) {

        final AgentExecutionContext finalCtx = agentExecutionContext;
        final List<ReasoningStep> finalHistory = resumedHistory;

        var scopeResult = ScopedValue.where(WorkspaceContext.RAW_USER_GOAL, goal)
                .where(WorkspaceContext.SESSION_ID, sessionId)
                .where(WorkspaceContext.USER_ID, capturedUserId)
                .where(WorkspaceContext.CHAT_ID, agentExecutionContext.chatId())
                .where(WorkspaceContext.AUTONOMY, agentExecutionContext.autonomyLevel())
                .where(WorkspaceContext.AGENT_ROLE, canonicalRole);

        if (agentExecutionContext.channelType() != null) {
            scopeResult = scopeResult.where(WorkspaceContext.CHANNEL_TYPE, agentExecutionContext.channelType());
        }
        if (agentExecutionContext.channelPlatformId() != null) {
            scopeResult =
                    scopeResult.where(WorkspaceContext.CHANNEL_PLATFORM_ID, agentExecutionContext.channelPlatformId());
        }

        return scopeResult.call(() -> {
            AgentResult resumeResult =
                    checkpointResumeHandler.handle(finalCtx, finalHistory, context, stateMachineRunner);
            if (resumeResult != null) {
                return resumeResult;
            } else if (context != null
                    && context.isResumeSignal()
                    && getLoopResultHandler().hasApprovedToolPending(sessionId, canonicalRole)) {
                return stateMachineRunner.run(finalCtx, engine, finalHistory);
            } else {
                return getLoopResultHandler()
                        .tryDelegateFinishFastPath(sessionId, finalHistory, context)
                        .orElseGet(() -> stateMachineRunner.run(finalCtx, engine, finalHistory));
            }
        });
    }

    @ActivateRequestContext
    public AgentResult run(String goal, ReasoningEngine engine, int maxIterations) {
        String sessionId = WorkspaceScopeHolder.getIfActive();
        return run(sessionId, AgentRoleEnum.COORDINATOR.toScopedName(), goal, engine, maxIterations);
    }

    private boolean isContinuationRequest(MessageContext context) {
        if (context == null || context.getMessage() == null) {
            return false;
        }
        String text = context.getMessage().text();
        return text != null && !text.isBlank() && intentClassificationService.isContinuationCommand(text);
    }

    private void recordDirectiveOutcome(String sessionId, String userId, boolean success) {
        if (directiveFeedbackPortInstance == null
                || directiveFeedbackPortInstance.isUnsatisfied()
                || sessionId == null
                || userId == null
                || userId.isBlank()) {
            return;
        }
        try {
            directiveFeedbackPortInstance.get().recordSessionOutcome(sessionId, userId, success);
        } catch (Exception e) {
            log.debugf("Directive feedback skipped for session %s: %s", sessionId, LogUtil.rootMessage(e));
        }
    }

    /**
     * Emits a lifecycle trace event for this turn. Null-safe: when {@link TracePublisher} was not
     * injected (manually-constructed test harness) the call is a no-op, so instrumentation can
     * never alter a turn's outcome. Honesty invariant — only real, observed transitions are
     * published; stations without a genuine execution boundary are left to render as
     * {@code NOT_INSTRUMENTED} on the cockpit rather than faked here.
     */
    private void publishTrace(
            TraceStage stage, String sessionId, StationCode station, String status, String detail, String paradigm) {
        AgentLoopTraceHelper.publishTrace(tracePublisher, stage, sessionId, station, status, detail, paradigm);
    }
}
