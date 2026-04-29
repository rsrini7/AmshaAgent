package com.arcclaw.core.agent.loop;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.core.agent.IntentClassificationService;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.reasoning.StructuredOutputMode;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.sse.AgentSseService;
import com.arcclaw.core.audit.AuditHashUtil;
import com.arcclaw.core.audit.ComplianceViolationException;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.structured.StructuredOutputException;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.arcclaw.core.util.ClassUtils;
import com.arcclaw.core.util.MdcManaged;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.smallrye.common.annotation.Identifier;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Lifecycle shell for the ArcClaw agent reasoning loop.
 *
 * <p>Handles session lifecycle only: MDC setup/teardown, OpenTelemetry span management,
 * checkpoint load/resume, compliance violation handling, and observer callbacks.
 * All reasoning iteration logic (Thought-Plan-Act-Observe, tool invocation, circuit breaker,
 * schema self-healing) is delegated to {@link AgentStateMachineRunner}.
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
    String structuredOutputModeStr;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    IntentClassificationService intentClassificationService;

    @ActivateRequestContext
    public AgentResult resume(String sessionId, String agentRole, String goal) {
        return run(sessionId, agentRole, goal, reasoningEngine, DEFAULT_MAX_ITERATIONS);
    }

    @ActivateRequestContext
    public AgentResult run(String goal, ReasoningEngine engine) {
        return run(goal, engine, DEFAULT_MAX_ITERATIONS);
    }

    @ActivateRequestContext
    public AgentResult run(String sessionId, String agentRole, String goal,
            ReasoningEngine engine, int maxIterations) {
        int budget = maxIterations;
        if (goal != null) {
            String lower = goal.toLowerCase();
            if (lower.contains("store") || lower.contains("remember") || lower.contains("call me") 
                || lower.contains("my name is") || lower.contains("refer to me as") || lower.contains("address me as")) {
                budget = Math.max(budget, 3);
            }
        }
        return run(sessionId, agentRole, goal, null, engine, budget, null);
    }

    @ActivateRequestContext
    public AgentResult run(String sessionId, String agentRole, String goal,
            ReasoningEngine engine, int maxIterations, MessageContext context) {
        return run(sessionId, agentRole, goal, null, engine, maxIterations, context);
    }

    @ActivateRequestContext
    public AgentResult run(String sessionId, String agentRole, String goal, String systemNote,
            ReasoningEngine engine, int maxIterations, MessageContext context) {

        final String canonicalRole = sessionLifecycleCoordinator.resolveCanonicalRole(sessionId, agentRole);
        return runWithRole(sessionId, canonicalRole, goal, systemNote, engine, maxIterations, context);
    }

    @ActivateRequestContext
    AgentResult runWithRole(String sessionId, String canonicalRole, String goal, String systemNote,
            ReasoningEngine engine, int maxIterations, MessageContext context) {

        final String capturedUserId = sessionLifecycleCoordinator.setupMdc(sessionId, canonicalRole);

        AgentResult result = null;
        List<ReasoningStep> resumedHistory = new ArrayList<>();

        Span span = observabilityManager.beginSession(sessionId);
        try (Scope scope = span.makeCurrent()) {

            var decision = checkpointResumeCoordinator.resolveResumePath(
                    sessionId, canonicalRole, capturedUserId, context);

            String effectiveRole = canonicalRole;
            if (decision instanceof CheckpointResumeCoordinator.ResumeDecision.ResumedHistory r) {
                // §Issue 6: Only restore history if it's a continuity command (follow-up).
                // Fresh goals should start with a clean history to avoid task confusion.
                if (intentClassificationService.isContinuityCommand(goal)) {
                    resumedHistory.addAll(r.history());
                    effectiveRole = r.checkpointRole();
                } else {
                    log.infof("[AgentLoop] Fresh goal detected for session %s - skipping history restoration from %s turn and resetting to canonical role %s to avoid task confusion.", 
                            sessionId, r.checkpointRole(), canonicalRole);
                    // effectiveRole remains canonicalRole
                }
            }

            StructuredOutputMode mode = Arrays.stream(StructuredOutputMode.values())
                .filter(m -> m.name().equalsIgnoreCase(structuredOutputModeStr))
                .findFirst()
                .orElse(StructuredOutputMode.NATIVE);

            Map<String, Object> mergedData = new HashMap<>();
            if (context != null) {
                mergedData.putAll(context.getData());
            }
            if (decision instanceof CheckpointResumeCoordinator.ResumeDecision.ResumedHistory r && r.contextDataJson() != null) {
                Map<String, Object> restored = agentStore.deserializeContextData(r.contextDataJson());
                if (restored != null) {
                    // §Issue 4.2: Prioritize restored context data (especially for cron metadata)
                    // during resumption to ensure scheduling continuity.
                    mergedData.putAll(restored);
                }
            }

            AgentExecutionContext agentExecutionContext = new AgentExecutionContext(
                    sessionId,
                    capturedUserId,
                    WorkspaceContext.requireChatId(),
                    effectiveRole,
                    goal,
                    WorkspaceContext.requireAutonomyOrSupervised(),
                    mode,
                    maxIterations,
                    decision instanceof CheckpointResumeCoordinator.ResumeDecision.ResumedHistory r ? r.iterationsUsed() : 0,
                    context != null ? context.getChannelType().name() : WorkspaceContext.channelType().orElse(null),
                    context != null && context.getUser() != null ? context.getUser().platformId() : null,
                    systemNote,
                    mergedData
            );

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
                scopeResult = scopeResult.where(WorkspaceContext.CHANNEL_PLATFORM_ID, agentExecutionContext.channelPlatformId());
            }

            result = scopeResult.call(() -> {
                        AgentResult resumeResult = checkpointResumeHandler.handle(finalCtx, finalHistory, context, stateMachineRunner);
                        if (resumeResult != null) {
                            return resumeResult;
                        } else if (context != null && context.isResumeSignal() && hasApprovedToolPending(sessionId, canonicalRole)) {
                            return stateMachineRunner.run(finalCtx, engine, finalHistory);
                        } else {
                            return tryDelegateFinishFastPath(sessionId, finalHistory, context)
                                    .orElseGet(() -> stateMachineRunner.run(
                                            finalCtx, engine, finalHistory));
                        }
                    });

            if (result instanceof AgentResult.AwaitingApproval) {
                tryStampChannelInfo(sessionId, canonicalRole, context);
            } else if (result instanceof AgentResult.AwaitingRetry awaitingRetry) {
                updateStoreForRetry(sessionId, canonicalRole, awaitingRetry, context);
            } else if (result != null && result.steps() != null && !result.steps().isEmpty()) {
                updateStoreForTerminal(sessionId, canonicalRole, result, goal);
            }

            return result;

        } catch (StructuredOutputException e) {
            String msg = AgentErrorMessageUtil.safeMessage(e);
            log.errorf("Structured output failure for session %s: %s", sessionId, msg);
            result = AgentResult.failed(msg, AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(resumedHistory));
            return result;

        } catch (BudgetExceededException e) {
            meterRegistry.counter("arcclaw.agent.loop.failure",
                    "role", canonicalRole != null ? canonicalRole : "unknown",
                    "exception", ClassUtils.getCleanName(e)).increment();
            throw e;
 
        } catch (BudgetWarningException e) {
            throw e;

        } catch (ComplianceViolationException cve) {
            result = handleComplianceViolation(sessionId, canonicalRole, cve);
            return result;

        } catch (Exception e) {
            meterRegistry.counter("arcclaw.agent.loop.failure",
                    "role", canonicalRole != null ? canonicalRole : "unknown",
                    "exception", ClassUtils.getCleanName(e)).increment();
            result = handleSystemError(sessionId, canonicalRole, e);
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

    private void updateStoreForRetry(String sessionId, String canonicalRole,
                                   AgentResult.AwaitingRetry awaitingRetry, MessageContext context) {
        log.warnf("Session %s paused due to rate limit - retry after %dms",
                sessionId, awaitingRetry.retryAfterMs());
        agentStore.load(sessionId, canonicalRole).ifPresent(cp -> {
            cp.setStatus(CheckpointStatus.AWAITING_RETRY);
            cp.setRetryAfterMs(awaitingRetry.retryAfterMs());
            agentStore.save(cp);

            if (context != null && context.getUser() != null && "TELEGRAM".equalsIgnoreCase(cp.getChannelType())) {
                long waitSec = awaitingRetry.retryAfterMs() / 1000;
                String notice = "⏳ Rate limit hit - I'll automatically retry in " + waitSec + " seconds.";
                channelPluginRegistry.find(context.getUser().channel().id())
                        .map(ChannelPlugin::sender)
                        .ifPresent(sender -> sender.sendRaw(context.getUser(), notice));
            }
        });
        agentSseService.publish(sessionId, new AgentSseService.AgentStatusEvent(
                sessionId, canonicalRole, "AWAITING_RETRY", Instant.now()
        ));
    }

    private void updateStoreForTerminal(String sessionId, String canonicalRole, AgentResult result, String goal) {
        if (result instanceof AgentResult.Finished) {
            agentStore.saveAndFinalize(sessionId, canonicalRole, result.steps(), CheckpointStatus.COMPLETED, goal);
            agentSseService.publish(sessionId, new AgentSseService.AgentStatusEvent(
                    sessionId, canonicalRole, "COMPLETED", Instant.now()
            ));
        } else if (result instanceof AgentResult.Failed f) {
            var cp = agentStore.save(sessionId, canonicalRole, result.steps(), goal);
            agentStore.markFailed(cp.getId(), f.reason());
            agentSseService.publish(sessionId, new AgentSseService.AgentStatusEvent(
                    sessionId, canonicalRole, "FAILED", Instant.now()
            ));
        } else if (result instanceof AgentResult.LoopExceeded l) {
            var cp = agentStore.save(sessionId, canonicalRole, result.steps(), goal);
            agentStore.markFailed(cp.getId(), "Max iterations (" + l.maxIterations() + ") exceeded");
            agentSseService.publish(sessionId, new AgentSseService.AgentStatusEvent(
                    sessionId, canonicalRole, "FAILED", Instant.now()
            ));
        }
    }

    private AgentResult handleComplianceViolation(String sessionId, String canonicalRole, ComplianceViolationException cve) {
        log.errorf("[SECURITY ALERT] ComplianceViolationException for session=%s eventType=%s - halting agent.",
                sessionId, cve.getEventType());
        String msg = AgentErrorMessageUtil.safeMessage(cve);
        AgentResult result = AgentResult.failed("COMPLIANCE_VIOLATION: " + msg,
                AgentResult.FailureCategory.COMPLIANCE_VIOLATION, List.of());
        String traceId = Span.current().getSpanContext().getTraceId();
        try {
            agentStore.load(sessionId, canonicalRole).ifPresent(cp ->
                agentStore.markFailed(cp.getId(), "COMPLIANCE_VIOLATION: " + msg,
                    "COMPLIANCE_VIOLATION", traceId, ClassUtils.getCleanName(cve))
            );
        } catch (Exception e) {
            log.debugf("Failed to mark checkpoint failed for session %s: %s", sessionId, ExceptionUtils.getRootCauseMessage(e));
        }
        return result;
    }

    private AgentResult handleSystemError(String sessionId, String canonicalRole, Exception e) {
        String errMsg = AgentErrorMessageUtil.safeMessage(e);
        log.errorf("Agent execution failed for session %s: %s", sessionId, errMsg, e);
        AgentResult result = AgentResult.failed("SYSTEM_ERROR: " + errMsg,
                AgentResult.FailureCategory.SYSTEM_ERROR, List.of());
        String traceId = Span.current().getSpanContext().getTraceId();
        try {
            agentStore.load(sessionId, canonicalRole).ifPresent(cp ->
                agentStore.markFailed(cp.getId(), "SYSTEM_ERROR: " + errMsg,
                    "SYSTEM_ERROR", traceId, ClassUtils.getCleanName(e))
            );
        } catch (Exception ex) {
            log.debugf("Failed to mark checkpoint failed for session %s: %s", sessionId, ExceptionUtils.getRootCauseMessage(ex));
        }
        return result;
    }

    private Optional<AgentResult> tryDelegateFinishFastPath(
            String sessionId, List<ReasoningStep> resumedHistory, MessageContext messageContext) {

        if (resumedHistory.isEmpty()) return Optional.empty();
        
        // §Issue 3: If this is a resume signal, CheckpointResumeHandler already handled it.
        // Don't fast-path here to avoid double-processing and double-send.
        if (messageContext != null && messageContext.isResumeSignal()) return Optional.empty();

        ReasoningStep last = resumedHistory.getLast();
        String toolName = last.action();
        String observation = last.observation();

        if (!ToolCapability.DELEGATE_TO_AGENT.toolName().equals(toolName)) return Optional.empty();
        if (observation == null || observation.isBlank()) return Optional.empty();

        // §Issue 3: Only fast-path if the goal is a generic follow-up.
        // Specific new goals must go through the LLM.
        String goal = (messageContext != null) ? messageContext.getGoal() : null;
        if (goal != null && !intentClassificationService.isContinuityCommand(goal)) {
            return Optional.empty();
        }

        if (last.success() != null) {
            if (!last.success()) {
                log.warnf("[AgentLoop] Delegate tool for session %s marked as failed - skipping fast-path.", sessionId);
                return Optional.empty();
            }
        } else {
            if (observation.startsWith("⏳")
                    || observation.startsWith("⚠️")
                    || observation.startsWith("Tool execution failed")
                    || observation.startsWith("Parameter ")
                    || observation.startsWith("❌")) {
                return Optional.empty();
            }
        }

        String lowerObs = observation.toLowerCase();
        String opening = lowerObs.length() > 150 ? lowerObs.substring(0, 150) : lowerObs;
        if (opening.contains("i was unable") || opening.contains("unable to fetch") 
                || opening.contains("could not find") || opening.contains("i cannot") 
                || opening.contains("could not retrieve")) {
            log.infof("[AgentLoop] Delegate result for session %s contains failure phrases - injecting observation to allow coordinator wrap-up without restart", sessionId);
            
            // Instead of empty (which causes restart), we return a finished result 
            // that forces the coordinator to see the failure in history and wrap up.
            List<ReasoningStep> historyWithFailure = new ArrayList<>(resumedHistory);
            historyWithFailure.add(ReasoningStep.observe(ToolCapability.DELEGATE_TO_AGENT.toolName(), observation, "Delegate failed - synthesize and finish"));
            
            return Optional.of(AgentResult.finished(observation, historyWithFailure));
        }

        log.infof("[AgentLoop] Fast-path: delegate_to_agent resume result used as FINISH " +
                "for session %s - skipping coordinator wrap-up LLM call", sessionId);
        return Optional.of(AgentResult.finished(observation, List.copyOf(resumedHistory)));
    }

    private void tryStampChannelInfo(String sessionId, String canonicalRole,
                                     MessageContext context) {
        if (context == null || context.getUser() == null) {
            log.warnf("No MessageContext user for session %s - " +
                      "approval timeout notification will be skipped.", sessionId);
            return;
        }
        try {
            agentStore.load(sessionId, canonicalRole).ifPresent(cp -> {
                cp.setChannelPlatformId(context.getUser().platformId());
                cp.setChannelType(context.getUser().channel().name());
                agentStore.save(cp);
            });
            log.debugf("Stamped channel info on checkpoint session=%s channel=%s platformId=%s",
                       sessionId, context.getUser().channel(), context.getUser().platformId());
        } catch (Exception e) {
            log.warnf("Failed to stamp channel info for session %s - Cause: %s",
                      sessionId, AgentErrorMessageUtil.safeMessage(e));
        }
    }

    private boolean hasApprovedToolPending(String sessionId, String role) {
        return agentStore.load(sessionId, role)
                .map(cp -> cp.getStatus() == CheckpointStatus.AWAITING_APPROVAL 
                        || cp.getStatus() == CheckpointStatus.RESUMING)
                .orElse(false);
    }

    @ActivateRequestContext
    public AgentResult run(String goal, ReasoningEngine engine, int maxIterations) {
        String sessionId = WorkspaceScopeHolder.getIfActive();
        return run(sessionId, AgentRoleEnum.COORDINATOR.toScopedName(),
                   goal, engine, maxIterations);
    }
}
