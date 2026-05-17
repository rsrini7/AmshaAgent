package com.arcclaw.core.agent.loop;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.IntentClassificationService;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.sse.AgentSseService;
import com.arcclaw.core.audit.ComplianceViolationException;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.arcclaw.core.util.ClassUtils;
import io.opentelemetry.api.trace.Span;
import org.jboss.logging.Logger;

public class LoopResultHandler {

    private static final Logger log = Logger.getLogger(LoopResultHandler.class);

    private final StatefulAgentStore agentStore;
    private final AgentSseService agentSseService;
    private final ChannelPluginRegistry channelPluginRegistry;
    private final IntentClassificationService intentClassificationService;

    public LoopResultHandler(StatefulAgentStore agentStore, AgentSseService agentSseService,
                             ChannelPluginRegistry channelPluginRegistry,
                             IntentClassificationService intentClassificationService) {
        this.agentStore = agentStore;
        this.agentSseService = agentSseService;
        this.channelPluginRegistry = channelPluginRegistry;
        this.intentClassificationService = intentClassificationService;
    }

    public void updateStoreForRetry(String sessionId, String canonicalRole,
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
                sessionId, canonicalRole, CheckpointStatus.AWAITING_RETRY.name(), Instant.now()
        ));
    }

    public void updateStoreForTerminal(String sessionId, String canonicalRole, String userId,
                                        AutonomyLevel autonomy, AgentResult result, String goal) {
        if (result instanceof AgentResult.Finished) {
            agentStore.saveAndFinalize(sessionId, canonicalRole, userId, autonomy, result.steps(), CheckpointStatus.COMPLETED, goal);
            agentSseService.publish(sessionId, new AgentSseService.AgentStatusEvent(
                    sessionId, canonicalRole, CheckpointStatus.COMPLETED.name(), Instant.now()
            ));
        } else if (result instanceof AgentResult.Failed f) {
            var cp = agentStore.save(sessionId, canonicalRole, userId, autonomy, result.steps(), goal);
            agentStore.markFailed(cp.getId(), f.reason());
            agentSseService.publish(sessionId, new AgentSseService.AgentStatusEvent(
                    sessionId, canonicalRole, CheckpointStatus.FAILED.name(), Instant.now()
            ));
        } else if (result instanceof AgentResult.LoopExceeded l) {
            var cp = agentStore.save(sessionId, canonicalRole, userId, autonomy, result.steps(), goal);
            agentStore.markFailed(cp.getId(), "Max iterations (" + l.maxIterations() + ") exceeded");
            agentSseService.publish(sessionId, new AgentSseService.AgentStatusEvent(
                    sessionId, canonicalRole, CheckpointStatus.FAILED.name(), Instant.now()
            ));
        }
    }

    public AgentResult handleComplianceViolation(String sessionId, String canonicalRole, ComplianceViolationException cve) {
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
            log.debugf("Failed to mark checkpoint failed %s: %s",
                    LogUtil.ctx(sessionId, canonicalRole), LogUtil.rootMessage(e));
        }
        return result;
    }

    public AgentResult handleSystemError(String sessionId, String canonicalRole, Exception e) {
        String errMsg = AgentErrorMessageUtil.safeMessage(e);
        log.errorf("Agent execution failed %s: %s\n%s",
                LogUtil.ctx(sessionId, canonicalRole), errMsg, LogUtil.trimmedStack(e));
        AgentResult result = AgentResult.failed("SYSTEM_ERROR: " + errMsg,
                AgentResult.FailureCategory.SYSTEM_ERROR, List.of());
        String traceId = Span.current().getSpanContext().getTraceId();
        try {
            agentStore.load(sessionId, canonicalRole).ifPresent(cp ->
                agentStore.markFailed(cp.getId(), "SYSTEM_ERROR: " + errMsg,
                    "SYSTEM_ERROR", traceId, ClassUtils.getCleanName(e))
            );
        } catch (Exception ex) {
            log.debugf("Failed to mark checkpoint failed %s: %s",
                    LogUtil.ctx(sessionId, canonicalRole), LogUtil.rootMessage(ex));
        }
        return result;
    }

    public void tryStampChannelInfo(String sessionId, String canonicalRole, MessageContext context) {
        if (context == null || context.getUser() == null) {
            log.warnf("No MessageContext user for session %s - " +
                      "approval timeout notification will be skipped.", sessionId);
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
            log.debugf("Stamped channel info on checkpoint session=%s channel=%s platformId=%s",
                       sessionId, context.getUser().channel(), context.getUser().platformId());
        } catch (Exception e) {
            log.warnf("Failed to stamp channel info %s: %s",
                      LogUtil.ctx(sessionId, canonicalRole), LogUtil.rootMessage(e));
        }
    }

    public boolean hasApprovedToolPending(String sessionId, String role) {
        return agentStore.load(sessionId, role)
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

            List<ReasoningStep> historyWithFailure = new ArrayList<>(resumedHistory);
            historyWithFailure.add(ReasoningStep.observe(ToolCapability.DELEGATE_TO_AGENT.toolName(), observation, "Delegate failed - synthesize and finish"));

            return Optional.of(AgentResult.finished(observation, historyWithFailure));
        }

        log.infof("[AgentLoop] Fast-path: delegate_to_agent resume result used as FINISH " +
                "for session %s - skipping coordinator wrap-up LLM call", sessionId);
        return Optional.of(AgentResult.finished(observation, List.copyOf(resumedHistory)));
    }
}
