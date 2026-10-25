package com.amsha.core.agent.loop;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelType;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.audit.ComplianceViolationException;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.core.util.ClassUtils;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;

import org.jboss.logging.Logger;

import io.opentelemetry.api.trace.Span;

public class LoopResultHandler {

    private static final Logger log = Logger.getLogger(LoopResultHandler.class);

    private final StatefulAgentStore agentStore;
    private final AgentSseService agentSseService;
    private final ChannelPluginRegistry channelPluginRegistry;
    private final IntentClassificationService intentClassificationService;
    private final ChatMemoryWriter chatMemoryWriter;

    public LoopResultHandler(
            StatefulAgentStore agentStore,
            AgentSseService agentSseService,
            ChannelPluginRegistry channelPluginRegistry,
            IntentClassificationService intentClassificationService,
            ChatMemoryWriter chatMemoryWriter) {
        this.agentStore = agentStore;
        this.agentSseService = agentSseService;
        this.channelPluginRegistry = channelPluginRegistry;
        this.intentClassificationService = intentClassificationService;
        this.chatMemoryWriter = chatMemoryWriter;
    }

    public void updateStoreForRetry(
            String sessionId,
            String canonicalRole,
            String userId,
            AutonomyLevel autonomy,
            AgentResult.AwaitingRetry awaitingRetry,
            String goal,
            MessageContext context) {
        AgentCheckpoint checkpoint = agentStore.load(sessionId, canonicalRole).orElseGet(() -> {
            log.infof("Creating retry checkpoint for rate-limited session %s", sessionId);
            return agentStore.save(sessionId, canonicalRole, userId, autonomy, awaitingRetry.steps(), goal);
        });
        if (checkpoint == null) {
            throw new IllegalStateException("Could not persist checkpoint for rate-limited session.");
        }

        checkpoint.setStatus(CheckpointStatus.AWAITING_RETRY);
        checkpoint.setRetryAfterMs(awaitingRetry.retryAfterMs());
        agentStore.save(checkpoint);
        log.warnf("Session %s paused due to rate limit - retry after %dms", sessionId, awaitingRetry.retryAfterMs());

        if (context != null && context.getUser() != null) {
            tryStampChannelInfo(sessionId, canonicalRole, context);
        }

        String checkpointChannelType = checkpoint.getChannelType();
        if (checkpointChannelType == null && context != null && context.getUser() != null) {
            checkpointChannelType = context.getUser().channel().name();
        }
        if (context != null
                && context.getUser() != null
                && context.getUser().channel() == ChannelType.TELEGRAM
                && ChannelType.TELEGRAM.name().equalsIgnoreCase(checkpointChannelType)) {
            long waitSec = awaitingRetry.retryAfterMs() / 1000;
            String notice = "⏳ Rate limit hit - I'll automatically retry in " + waitSec + " seconds.";
            channelPluginRegistry
                    .find(context.getUser().channel().id())
                    .map(ChannelPlugin::sender)
                    .ifPresent(sender -> sender.sendRaw(context.getUser(), notice));
        }
        agentSseService.publish(
                sessionId,
                new AgentSseService.AgentStatusEvent(
                        sessionId, canonicalRole, CheckpointStatus.AWAITING_RETRY.name(), Instant.now(), (long)
                                awaitingRetry.retryAfterMs()));
    }

    public void updateStoreForTerminal(
            String sessionId,
            String canonicalRole,
            String userId,
            AutonomyLevel autonomy,
            AgentResult result,
            String goal) {
        if (result instanceof AgentResult.Finished) {
            persistTerminalAnswer(sessionId, result);
            CheckpointStatus finalStatus = CheckpointStatus.COMPLETED;
            if (agentStore != null) {
                var current = agentStore.load(sessionId, canonicalRole);
                if (current != null && current.isPresent() && current.get().getStatus() == CheckpointStatus.FAILED) {
                    finalStatus = CheckpointStatus.FAILED;
                }
                agentStore.saveAndFinalize(
                        sessionId, canonicalRole, userId, autonomy, result.steps(), finalStatus, goal);
            }
            if (agentSseService != null) {
                agentSseService.publish(
                        sessionId,
                        new AgentSseService.AgentStatusEvent(
                                sessionId, canonicalRole, finalStatus.name(), Instant.now()));
            }
        } else if (result instanceof AgentResult.StructuredFinished) {
            persistTerminalAnswer(sessionId, result);
            if (agentStore != null) {
                agentStore.saveAndFinalize(
                        sessionId, canonicalRole, userId, autonomy, result.steps(), CheckpointStatus.COMPLETED, goal);
            }
            if (agentSseService != null) {
                agentSseService.publish(
                        sessionId,
                        new AgentSseService.AgentStatusEvent(
                                sessionId, canonicalRole, CheckpointStatus.COMPLETED.name(), Instant.now()));
            }
        } else if (result instanceof AgentResult.Failed f) {
            persistTerminalAnswer(sessionId, result);
            if (agentStore != null) {
                var cp = agentStore.save(sessionId, canonicalRole, userId, autonomy, result.steps(), goal);
                if (cp != null) {
                    agentStore.markFailed(cp.getId(), f.reason());
                }
            }
            if (agentSseService != null) {
                agentSseService.publish(
                        sessionId,
                        new AgentSseService.AgentStatusEvent(
                                sessionId, canonicalRole, CheckpointStatus.FAILED.name(), Instant.now()));
            }
        } else if (result instanceof AgentResult.LoopExceeded l) {
            // Quarantine only the failed turn's dangling tool requests. Preserve the
            // session history and completed tool results so the same session can recover
            // on its next goal without replaying an invalid/pending call.
            if (chatMemoryWriter != null) {
                chatMemoryWriter.scrubUnfulfilledToolCalls(sessionId);
            }
            if (agentStore != null) {
                var cp = agentStore.save(sessionId, canonicalRole, userId, autonomy, result.steps(), goal);
                if (cp != null) {
                    agentStore.markFailed(cp.getId(), "Max iterations (" + l.maxIterations() + ") exceeded");
                }
            }
            if (agentSseService != null) {
                agentSseService.publish(
                        sessionId,
                        new AgentSseService.AgentStatusEvent(
                                sessionId, canonicalRole, CheckpointStatus.FAILED.name(), Instant.now()));
            }
        }
    }

    private void persistTerminalAnswer(String sessionId, AgentResult result) {
        if (chatMemoryWriter == null) {
            return;
        }
        String answer =
                switch (result) {
                    case AgentResult.Finished f -> f.answer();
                    case AgentResult.StructuredFinished sf -> sf.answer();
                    case AgentResult.Failed f -> f.reason();
                    default -> null;
                };
        chatMemoryWriter.saveAnswer(sessionId, answer);
    }

    public AgentResult handleComplianceViolation(
            String sessionId, String canonicalRole, ComplianceViolationException cve) {
        log.errorf(
                "[SECURITY ALERT] ComplianceViolationException for session=%s eventType=%s - halting agent.",
                sessionId, cve.getEventType());
        String msg = AgentErrorMessageUtil.safeMessage(cve);
        AgentResult result = AgentResult.failed(
                "COMPLIANCE_VIOLATION: " + msg, AgentResult.FailureCategory.COMPLIANCE_VIOLATION, List.of());
        String traceId = Span.current().getSpanContext().getTraceId();
        try {
            agentStore
                    .load(sessionId, canonicalRole)
                    .ifPresent(cp -> agentStore.markFailed(
                            cp.getId(),
                            "COMPLIANCE_VIOLATION: " + msg,
                            "COMPLIANCE_VIOLATION",
                            traceId,
                            ClassUtils.getCleanName(cve)));
        } catch (Exception e) {
            log.debugf(
                    "Failed to mark checkpoint failed %s: %s",
                    LogUtil.ctx(sessionId, canonicalRole), LogUtil.rootMessage(e));
        }
        return result;
    }

    public AgentResult handleSystemError(String sessionId, String canonicalRole, Exception e) {
        String errMsg = AgentErrorMessageUtil.safeMessage(e);
        log.errorf(
                "Agent execution failed %s: %s\n%s",
                LogUtil.ctx(sessionId, canonicalRole), errMsg, LogUtil.trimmedStack(e));
        AgentResult result = AgentResult.failed(
                AgentErrorMessageUtil.SYSTEM_ERROR_PREFIX + errMsg,
                AgentResult.FailureCategory.SYSTEM_ERROR,
                List.of());
        String traceId = Span.current().getSpanContext().getTraceId();
        try {
            agentStore
                    .load(sessionId, canonicalRole)
                    .ifPresent(cp -> agentStore.markFailed(
                            cp.getId(),
                            AgentErrorMessageUtil.SYSTEM_ERROR_PREFIX + errMsg,
                            "SYSTEM_ERROR",
                            traceId,
                            ClassUtils.getCleanName(e)));
        } catch (Exception ex) {
            log.debugf(
                    "Failed to mark checkpoint failed %s: %s",
                    LogUtil.ctx(sessionId, canonicalRole), LogUtil.rootMessage(ex));
        }
        return result;
    }

    public void tryStampChannelInfo(String sessionId, String canonicalRole, MessageContext context) {
        if (context == null || context.getUser() == null) {
            log.warnf(
                    "No MessageContext user for session %s - " + "approval timeout notification will be skipped.",
                    sessionId);
            return;
        }
        try {
            agentStore.load(sessionId, canonicalRole).ifPresent(cp -> {
                // CRITICAL: Only update channel fields. Do NOT re-save the entire
                // checkpoint — the load() may return stale status (e.g. FAILED from
                // a previous run) which would overwrite the AWAITING_APPROVAL status
                // that was just persisted by saveCheckpointWithSteps().
                String platformId = context.getUser().platformId();
                String channelType = context.getUser().channel().name();
                if (cp.getId() != null) {
                    agentStore.stampChannelInfo(cp.getId(), channelType, platformId);
                }
            });
            log.debugf(
                    "Stamped channel info on checkpoint session=%s channel=%s platformId=%s",
                    sessionId, context.getUser().channel(), context.getUser().platformId());
        } catch (Exception e) {
            log.warnf(
                    "Failed to stamp channel info %s: %s",
                    LogUtil.ctx(sessionId, canonicalRole), LogUtil.rootMessage(e));
        }
    }

    public boolean hasApprovedToolPending(String sessionId, String role) {
        return agentStore
                .load(sessionId, role)
                .map(cp -> cp.getStatus() == CheckpointStatus.AWAITING_APPROVAL
                        || cp.getStatus() == CheckpointStatus.RESUMING)
                .orElse(false);
    }

    public Optional<AgentResult> tryDelegateFinishFastPath(
            String sessionId, List<ReasoningStep> resumedHistory, MessageContext messageContext) {

        if (resumedHistory.isEmpty()) return Optional.empty();

        if (messageContext != null && messageContext.isResumeSignal()) return Optional.empty();

        ReasoningStep last = resumedHistory.getLast();
        String toolName = last.action();
        String observation = last.observation();

        if (!ToolCapability.DELEGATE_TO_AGENT.toolName().equals(toolName)) return Optional.empty();
        if (observation == null || observation.isBlank()) return Optional.empty();

        String goal = (messageContext != null) ? messageContext.getGoal() : null;
        if (goal != null && !intentClassificationService.isResumeCommand(goal)) {
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

        String lowerObs = observation.toLowerCase(Locale.ROOT);
        String opening = lowerObs.length() > 150 ? lowerObs.substring(0, 150) : lowerObs;
        if (opening.contains("i was unable")
                || opening.contains("unable to fetch")
                || opening.contains("could not find")
                || opening.contains("i cannot")
                || opening.contains("could not retrieve")) {
            log.infof(
                    "[AgentLoop] Delegate result for session %s contains failure phrases - injecting observation to allow coordinator wrap-up without restart",
                    sessionId);

            List<ReasoningStep> historyWithFailure = new ArrayList<>(resumedHistory);
            historyWithFailure.add(ReasoningStep.observeStep(
                    ToolCapability.DELEGATE_TO_AGENT.toolName(),
                    observation,
                    "Delegate failed - synthesize and finish"));

            return Optional.of(AgentResult.finished(observation, historyWithFailure));
        }

        log.infof(
                "[AgentLoop] Fast-path: garuda resume result used as FINISH "
                        + "for session %s - skipping coordinator wrap-up LLM call",
                sessionId);
        return Optional.of(AgentResult.finished(observation, List.copyOf(resumedHistory)));
    }
}
