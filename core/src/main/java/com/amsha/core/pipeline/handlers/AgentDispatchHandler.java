package com.amsha.core.pipeline.handlers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import com.amsha.channel.api.ApprovalPrompt;
import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.governance.BudgetExceededException;
import com.amsha.core.governance.BudgetWarningException;
import com.amsha.core.pipeline.HandleResult;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.pipeline.MessageHandler;
import com.amsha.core.pipeline.SessionContext;
import com.amsha.core.pipeline.TurnDispatchService;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.InputSanitizer;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.core.speech.OutputMode;
import com.amsha.core.speech.OutputPolicyContext;
import com.amsha.core.speech.SpeechOutputPolicy;
import com.amsha.core.speech.SpeechReplyService;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.AgentResultFormatter;
import com.amsha.core.util.TimeContextInjector;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Dispatches the enriched request to the Agent State Machine.
 * <p>
 * When the resolved role is a skill role (e.g. {@code "skill:code-reviewer"}),
 * the skill's {@code maxIterations} from its frontmatter definition is used
 * instead of the hardcoded default. This ensures skills configured with higher
 * iteration counts (e.g. researcher at 7) actually get those iterations.
 */
@ApplicationScoped
public class AgentDispatchHandler implements MessageHandler {
    private static final Logger log = Logger.getLogger(AgentDispatchHandler.class);
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\(([^)]+)\\)");
    private static final int DEFAULT_MAX_ITERATIONS = 5;

    @ConfigProperty(name = "amsha.agent.default-timezone", defaultValue = "Asia/Kolkata")
    String defaultTimezone;

    @Inject
    AgentLoop agentLoop;

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    SkillRegistry skillRegistry;

    @Inject
    ChannelPluginRegistry channelPluginRegistry;

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    IntentClassificationService intentClassifier;

    @Inject
    @InputSanitizer.PII
    InputSanitizer piiSanitizer;

    @Inject
    SpeechOutputPolicy speechOutputPolicy;

    @Inject
    SpeechReplyService speechReplyService;

    @Override
    public int priority() {
        return 70;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        SessionContext session = context.getSessionContext()
                .orElseThrow(
                        () -> new IllegalStateException("AgentDispatchHandler requires SessionContext to be populated "
                                + "by SessionResolutionHandler (priority 40) first. "
                                + "Check handler priority ordering."));

        String sessionId = session.sessionId();
        String role = context.getAgentRole().orElse(AgentRoleEnum.COORDINATOR.toScopedName());

        String goal = resolveGoal(context, session, sessionId);
        int maxIterations = resolveMaxIterations(context, role, sessionId);
        String chatId = resolveChatId(context);

        AgentResult result;
        try {
            result = runAgent(context, session, sessionId, role, goal, maxIterations, chatId);
        } catch (BudgetExceededException | BudgetWarningException e) {
            throw e;
        } catch (Exception e) {
            return handleRunException(context, goal, e);
        }

        context.setData(TurnDispatchService.DATA_AGENT_RESULT, result);
        formatAndSendReply(context, result, sessionId);
        return HandleResult.STOP;
    }

    private String resolveGoal(MessageContext context, SessionContext _session, String sessionId) {
        String rawGoal = (context.getGoal() != null && !context.getGoal().isBlank())
                ? context.getGoal()
                : (context.getMessage() != null ? context.getMessage().text() : "");

        String goal = normalizeGoal(rawGoal);
        if (piiSanitizer != null) {
            goal = piiSanitizer.sanitize(goal);
        }

        String rawMessageText =
                context.getMessage() != null ? context.getMessage().text() : "";
        // Retry and continuation commands both resume the prior task context.
        // Execution follow-ups ("run", "execute", "in java") preserve the ROLE but use the NEW goal.
        boolean isRetryCommand = intentClassifier.isRetryCommand(rawMessageText);
        boolean isContinuationCommand = intentClassifier.isContinuationCommand(rawMessageText);
        boolean isResumeCommand = isRetryCommand || isContinuationCommand;

        String restoredGoal = null;
        if (isResumeCommand) {
            restoredGoal = agentStore
                    .findLatestNonArchivedCheckpoint(sessionId)
                    .or(() -> agentStore.findLatestCheckpoint(sessionId))
                    .map(AgentCheckpoint::getOriginalGoal)
                    .filter(g -> g != null && !g.isBlank() && !intentClassifier.isResumeCommand(g))
                    .orElse(null);

            if (restoredGoal != null && !restoredGoal.isBlank()) {
                log.infof(
                        "[AgentDispatch] Resume command detected for session %s (retry=%b, continue=%b) - restoring original goal: %s",
                        sessionId, isRetryCommand, isContinuationCommand, restoredGoal);
                goal = normalizeGoal(restoredGoal);
                TimeContextInjector.inject(context, goal, defaultTimezone);
            } else {
                log.warnf(
                        "[AgentDispatch] Resume command detected for session %s (retry=%b, continue=%b) but no suitable original goal found in history.",
                        sessionId, isRetryCommand, isContinuationCommand);
            }
        }

        String logSafeRawGoal = piiSanitizer != null ? piiSanitizer.sanitize(rawGoal) : rawGoal;
        String logSafeRawMessage = piiSanitizer != null ? piiSanitizer.sanitize(rawMessageText) : rawMessageText;
        String logSafeRestoredGoal =
                piiSanitizer != null && restoredGoal != null ? piiSanitizer.sanitize(restoredGoal) : restoredGoal;

        log.infof(
                "[AgentDispatch] Goal Resolution: rawGoal='%s' rawMessage='%s' isRetry=%b isContinue=%b isResume=%b restoredGoal='%s' finalGoal='%s'",
                logSafeRawGoal,
                logSafeRawMessage,
                isRetryCommand,
                isContinuationCommand,
                isResumeCommand,
                logSafeRestoredGoal,
                goal);

        return goal;
    }

    private int resolveMaxIterations(MessageContext context, String role, String sessionId) {
        Object requestedMax = context.getData(TurnDispatchService.DATA_MAX_ITERATIONS);
        if (requestedMax instanceof Integer maxIterations && maxIterations > 0) {
            return maxIterations;
        }
        int maxIterations = DEFAULT_MAX_ITERATIONS;
        if (AgentRoleEnum.isSkillRole(role)) {
            String skillName = AgentRoleEnum.skillName(role);
            maxIterations = skillRegistry
                    .findByName(skillName)
                    .map(entry -> entry.definition().maxIterations())
                    .orElse(DEFAULT_MAX_ITERATIONS);
            log.infof(
                    "Dispatching message for session %s to skill '%s' (maxIterations=%d)",
                    sessionId, skillName, maxIterations);
        } else {
            log.infof("Dispatching message for session %s to agent %s", sessionId, role);
        }
        return maxIterations;
    }

    private String resolveChatId(MessageContext context) {
        String chatId = context.getChatId();
        if ((chatId == null || chatId.isBlank()) && context.getUser() != null) {
            chatId = context.getUser().channelId();
            log.debugf("[AgentDispatch] getChatId() was null - falling back to channelId=%s", chatId);
        }
        return chatId != null ? chatId : "unknown";
    }

    private AgentResult runAgent(
            MessageContext context,
            SessionContext session,
            String sessionId,
            String role,
            String goal,
            int maxIterations,
            String chatId)
            throws Exception {
        if (!agentStore.markResuming(sessionId)) {
            log.warnf(
                    "[AgentDispatch] session %s is already busy processing another turn. Rejecting new goal.",
                    sessionId);
            channelPluginRegistry
                    .find(context.getChannelType())
                    .map(ChannelPlugin::sender)
                    .ifPresent(
                            s -> s.sendRaw(
                                    context.getUser(),
                                    "⚠️ I'm currently processing another request. Your message was not queued - please resend it once I finish."));
            throw new RuntimeException("Busy session");
        }

        try {
            String channel = context.getChannelType().name();
            Callable<AgentResult> op =
                    () -> agentLoop.run(sessionId, role, goal, reasoningEngine, maxIterations, context);
            return WorkspaceContext.<AgentResult>run(
                    sessionId,
                    context.getUser() != null ? context.getUser().platformId() : "unknown",
                    chatId,
                    session.autonomyLevel(),
                    role,
                    channel,
                    context.getUser() != null ? context.getUser().platformId() : null,
                    op);
        } finally {
            agentStore.clearResuming(sessionId);
        }
    }

    private HandleResult handleRunException(MessageContext context, String goal, Exception e) {
        if ("Busy session".equals(e.getMessage())) {
            return HandleResult.STOP;
        }
        log.errorf("[AgentDispatch] Error processing command '%s': %s", goal, LogUtil.rootMessage(e));
        log.debug("Full agent dispatch failure stack trace:", e);

        channelPluginRegistry
                .find(context.getChannelType())
                .map(ChannelPlugin::sender)
                .ifPresentOrElse(
                        s -> s.sendRaw(context.getUser(), "❌ An internal error occurred. Please try again."),
                        () -> log.warnf(
                                "AgentDispatchHandler: no ChannelPlugin for type %s - error reply dropped",
                                context.getChannelType()));
        return HandleResult.STOP;
    }

    /**
     * Formats and delivers the agent result. Runs on the worker thread provided by
     * {@link com.amsha.core.pipeline.TurnDispatchService#dispatchAsync} (a {@code ManagedExecutor}),
     * never on the Vert.x event loop, so the synchronous speech synthesis inside
     * {@link #deliverWithOptionalAudio} is safe to block. The event-loop guard in
     * {@code SpeechReplyService} remains a defensive fail-closed net for any anomalous caller.
     */
    private void formatAndSendReply(MessageContext context, AgentResult result, String sessionId) {
        channelPluginRegistry
                .find(context.getChannelType())
                .map(ChannelPlugin::sender)
                .ifPresentOrElse(
                        s -> {
                            if (result instanceof AgentResult.AwaitingApproval aa) {
                                s.send(
                                        context.getUser(),
                                        List.of(new ApprovalPrompt(aa.toolName(), aa.paramsJson(), sessionId)));
                            } else {
                                String output = AgentResultFormatter.format(result);
                                deliverWithOptionalAudio(context, s, result, output);
                            }
                        },
                        () -> log.warnf(
                                "AgentDispatchHandler: no ChannelPlugin for type %s - result reply dropped",
                                context.getChannelType()));
    }

    /**
     * Sends the answer and adds synthesized audio only for a successfully finished turn. Approval
     * prompts, failed results, intermediate statuses, and internal notifications are never
     * synthesized; they are delivered as text exactly as before.
     */
    private void deliverWithOptionalAudio(
            MessageContext context, ChannelSender sender, AgentResult result, String output) {
        if (!(result instanceof AgentResult.Finished)) {
            sender.sendRaw(context.getUser(), output);
            return;
        }
        // Resolve the policy text from the goal, falling back to the current message text. Some
        // dispatch paths reach here with an empty goal while the user's directive ("reply with audio")
        // lives in the message body; without the fallback the explicit request would be missed.
        OutputMode mode = speechOutputPolicy.resolve(
                OutputPolicyContext.of(resolvePolicyText(context), hadInboundAudio(context)));
        if (mode == OutputMode.AUDIO_ONLY) {
            if (!speechReplyService.deliver(sender, context.getUser(), mode, output)) {
                sender.sendRaw(context.getUser(), output);
            }
            return;
        }
        sender.sendRaw(context.getUser(), output);
        if (mode == OutputMode.TEXT_AND_AUDIO) {
            speechReplyService.deliver(sender, context.getUser(), mode, output);
        }
    }

    /** Channel-neutral detection: true when this turn carried an inbound audio attachment. */
    private static boolean hadInboundAudio(MessageContext context) {
        if (context.getMessage() == null || context.getMessage().attachments() == null) {
            return false;
        }
        for (MediaAttachment attachment : context.getMessage().attachments()) {
            if (attachment != null && attachment.type() == AttachmentType.AUDIO) {
                return true;
            }
        }
        return false;
    }

    /**
     * Text used to resolve the speech output mode for this turn. Prefers the stored goal, but falls
     * back to the current message text so an explicit audio directive still reaches the policy when
     * the goal is empty. The fallback never reimplements channel-specific attachment inspection.
     */
    private static String resolvePolicyText(MessageContext context) {
        String goal = context.getGoal();
        if (goal != null && !goal.isBlank()) {
            return goal;
        }
        return context.getMessage() != null ? context.getMessage().text() : "";
    }

    static String normalizeGoal(String goal) {
        if (goal == null || goal.isBlank()) return goal;
        return MARKDOWN_LINK_PATTERN.matcher(goal).replaceAll("$2");
    }
}
