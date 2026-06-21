package com.arcclaw.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.arcclaw.core.agent.IntentClassificationService;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.reasoning.StructuredOutputMode;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.sse.AgentSseService;
import com.arcclaw.core.audit.AuditHashUtil;
import com.arcclaw.core.audit.ComplianceViolationException;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.structured.StructuredOutputException;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.arcclaw.core.util.ClassUtils;
import com.arcclaw.core.util.MdcManaged;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.smallrye.common.annotation.Identifier;

/**
 * Lifecycle shell for the ArcClaw agent reasoning loop.
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

    private LoopResultHandler loopResultHandler;

    private LoopResultHandler getLoopResultHandler() {
        var h = this.loopResultHandler;
        if (h == null) {
            h = new LoopResultHandler(agentStore, agentSseService, channelPluginRegistry, intentClassificationService);
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

        AgentResult result = null;
        List<ReasoningStep> resumedHistory = new ArrayList<>();

        Span span = observabilityManager.beginSession(sessionId, capturedUserId, canonicalRole);
        try (Scope _ = span.makeCurrent()) {
            try {
                var decision = checkpointResumeCoordinator.resolveResumePath(
                        sessionId, canonicalRole, capturedUserId, context);

                String effectiveRole = canonicalRole;
                if (decision instanceof CheckpointResumeCoordinator.ResumeDecision.ResumedHistory r) {
                    if (intentClassificationService.isContinuityCommand(goal)) {
                        resumedHistory.addAll(r.history());
                        effectiveRole = r.checkpointRole();
                    } else {
                        log.infof(
                                "[AgentLoop] Fresh goal detected for session %s - skipping history restoration from %s turn and resetting to canonical role %s to avoid task confusion.",
                                sessionId, r.checkpointRole(), canonicalRole);
                    }
                }

                StructuredOutputMode mode =
                        (structuredOutputModeInstance != null && !structuredOutputModeInstance.isUnsatisfied())
                                ? structuredOutputModeInstance.get()
                                : StructuredOutputMode.NATIVE;

                AgentExecutionContext agentExecutionContext = createExecutionContext(
                        sessionId,
                        capturedUserId,
                        effectiveRole,
                        goal,
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
                        goal,
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
                                    goal);
                }

                return result;

            } catch (StructuredOutputException e) {
                String msg = AgentErrorMessageUtil.safeMessage(e);
                log.errorf("Structured output failure for session %s: %s", sessionId, msg);
                result = AgentResult.failed(msg, AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(resumedHistory));
                return result;

            } catch (BudgetExceededException e) {
                meterRegistry
                        .counter(
                                "arcclaw.agent.loop.failure",
                                "role",
                                canonicalRole != null ? canonicalRole : "unknown",
                                "exception",
                                ClassUtils.getCleanName(e))
                        .increment();
                throw e;

            } catch (BudgetWarningException e) {
                throw e;

            } catch (ComplianceViolationException cve) {
                result = getLoopResultHandler().handleComplianceViolation(sessionId, canonicalRole, cve);
                return result;

            } catch (Exception e) {
                meterRegistry
                        .counter(
                                "arcclaw.agent.loop.failure",
                                "role",
                                canonicalRole != null ? canonicalRole : "unknown",
                                "exception",
                                ClassUtils.getCleanName(e))
                        .increment();
                result = getLoopResultHandler().handleSystemError(sessionId, canonicalRole, e);
                return result;

            } finally {
                try {
                    String goalHash = hashUtil.sha256hex(goal);
                    observabilityManager.notifyLoopComplete(sessionId, capturedUserId, canonicalRole, goalHash, result);
                } finally {
                    observabilityManager.endSession(sessionId);
                    sessionLifecycleCoordinator.teardownMdc();
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

        return new AgentExecutionContext(
                sessionId,
                capturedUserId,
                chatId,
                effectiveRole,
                goal,
                autonomy,
                mode,
                maxIterations,
                decision instanceof CheckpointResumeCoordinator.ResumeDecision.ResumedHistory r
                        ? r.iterationsUsed()
                        : 0,
                channelTypeStr,
                channelPlatformId,
                systemNote,
                mergedData);
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
}
