package com.arcclaw.core.pipeline.handlers;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ReplyNode;
import com.arcclaw.core.agent.IntentClassificationService;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.pipeline.SessionContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.InputSanitizer;
import com.arcclaw.core.skill.SkillRegistry;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.AgentResultFormatter;
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

    @Override
    public int priority() {
        return 70;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        SessionContext session = context.getSessionContext()
            .orElseThrow(() -> new IllegalStateException(
                "AgentDispatchHandler requires SessionContext to be populated " +
                "by SessionResolutionHandler (priority 40) first. " +
                "Check handler priority ordering."));

        String sessionId = session.sessionId();
        String role = context.getAgentRole()
                .orElse(AgentRoleEnum.COORDINATOR.toScopedName());

        // Prioritize the sanitized goal from reflexes (StoreReflex, PII redact) 
        // over the raw message text.
        String rawGoal = (context.getGoal() != null && !context.getGoal().isBlank())
                ? context.getGoal()
                : (context.getMessage() != null ? context.getMessage().text() : "");

        // §Retry-Fix: Restore originalGoal from latest checkpoint for continuity commands (e.g. "try again")
        String goal = normalizeGoal(rawGoal);
        if (piiSanitizer != null) {
            goal = piiSanitizer.sanitize(goal);
        }
        
        String rawMessageText = context.getMessage() != null ? context.getMessage().text() : "";
        String restoredGoal = null;
        boolean isContinuity = intentClassifier.isContinuityCommand(rawMessageText);

        if (isContinuity) {
            // We search for the most recent non-archived checkpoint that has a MEANINGFUL originalGoal.
            restoredGoal = agentStore.findLatestNonArchivedCheckpoint(sessionId)
                    .map(AgentCheckpoint::getOriginalGoal)
                    .filter(g -> g != null && !g.isBlank() && !intentClassifier.isContinuityCommand(g)) 
                    .orElse(null);

            if (restoredGoal != null && !restoredGoal.isBlank()) {
                log.infof("[AgentDispatch] Continuity detected for session %s - restoring original goal: %s", 
                        sessionId, restoredGoal);
                goal = normalizeGoal(restoredGoal);
            } else {
                log.warnf("[AgentDispatch] Continuity detected for session %s but no suitable original goal found in history.", sessionId);
            }
        }
        
        String logSafeRawGoal = piiSanitizer != null ? piiSanitizer.sanitize(rawGoal) : rawGoal;
        String logSafeRawMessage = piiSanitizer != null ? piiSanitizer.sanitize(rawMessageText) : rawMessageText;
        String logSafeRestoredGoal = piiSanitizer != null && restoredGoal != null ? piiSanitizer.sanitize(restoredGoal) : restoredGoal;

        log.infof("[AgentDispatch] Goal Resolution: rawGoal='%s' rawMessage='%s' isContinuity=%b restoredGoal='%s' finalGoal='%s'",
                logSafeRawGoal, logSafeRawMessage, isContinuity, logSafeRestoredGoal, goal);

        // Resolve maxIterations...
        int maxIterations = DEFAULT_MAX_ITERATIONS;
        if (AgentRoleEnum.isSkillRole(role)) {
            String skillName = AgentRoleEnum.skillName(role);
            maxIterations = skillRegistry.findByName(skillName)
                    .map(entry -> entry.definition().maxIterations())
                    .orElse(DEFAULT_MAX_ITERATIONS);
            log.infof("Dispatching message for session %s to skill '%s' (maxIterations=%d)",
                    sessionId, skillName, maxIterations);
        } else {
            log.infof("Dispatching message for session %s to agent %s", sessionId, role);
        }

        final int resolvedMaxIterations = maxIterations;
        final AutonomyLevel finalLevel = session.autonomyLevel();

        // getChatId() can be null on some Telegram paths - fall back to channelId
        String chatId = context.getChatId();
        if ((chatId == null || chatId.isBlank()) && context.getUser() != null) {
            chatId = context.getUser().channelId();
            log.debugf("[AgentDispatch] getChatId() was null - falling back to channelId=%s", chatId);
        }
        final String resolvedChatId = chatId != null ? chatId : "unknown";

        final String resolvedGoal = goal;
        final String capturedUserId = context.getUser() != null ? context.getUser().platformId() : "unknown";
        final String canonicalRole = role;

        AgentResult result;
        try {
            if (!agentStore.markResuming(sessionId)) {
                log.warnf("[AgentDispatch] session %s is already busy processing another turn. Rejecting new goal.", sessionId);
                channelPluginRegistry.find(context.getChannelType())
                    .map(ChannelPlugin::sender)
                    .ifPresent(s -> s.sendRaw(context.getUser(), "⚠️ I'm currently processing another request. Your message was not queued - please resend it once I finish."));
                return HandleResult.STOP;
            }

            try {
                String channel = context.getChannelType().name();
                Callable<AgentResult> op = () -> agentLoop.run(
                        sessionId,
                        canonicalRole,
                        resolvedGoal,
                        reasoningEngine,
                        resolvedMaxIterations,
                        context
                    );
                result = WorkspaceContext.<AgentResult>run(
                    sessionId,
                    capturedUserId,
                    resolvedChatId,
                    finalLevel,
                    canonicalRole,
                    channel,
                    context.getUser() != null ? context.getUser().platformId() : null,
                    op
                );
            } finally {
                agentStore.clearResuming(sessionId);
            }
        } catch (BudgetExceededException | BudgetWarningException e) {
            throw e;   // propagate typed - caller maps to 429
        } catch (Exception e) {
            log.errorf("[AgentDispatch] Error processing command '%s': %s", goal, e.getMessage());
            log.debug("Full agent dispatch failure stack trace:", e);
            
            channelPluginRegistry.find(context.getChannelType())
                .map(ChannelPlugin::sender)
                .ifPresentOrElse(
                    s -> s.sendRaw(context.getUser(), "❌ An internal error occurred. Please try again."),
                    () -> log.warnf("AgentDispatchHandler: no ChannelPlugin for type %s - error reply dropped", context.getChannelType())
                );
            return HandleResult.STOP;  // stop pipeline gracefully - do not throw
        }

        String output = AgentResultFormatter.format(result);
        channelPluginRegistry.find(context.getChannelType())
            .map(ChannelPlugin::sender)
            .ifPresentOrElse(
                s -> {
                    if (result instanceof AgentResult.AwaitingApproval aa && aa.toolName().startsWith(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX)) {
                        // Pass down as a structured reply so plugins (e.g. Telegram) can build keyboards
                        s.send(context.getUser(), List.of(
                            new ReplyNode.Text(output),
                            new ReplyNode.ApprovalPrompt(aa.toolName(), aa.paramsJson(), sessionId)
                        ));
                    } else {
                        s.sendRaw(context.getUser(), output);
                    }
                },
                () -> log.warnf("AgentDispatchHandler: no ChannelPlugin for type %s - result reply dropped", context.getChannelType())
            );

        return HandleResult.STOP;
    }

    static String normalizeGoal(String goal) {
        if (goal == null || goal.isBlank()) return goal;
        return MARKDOWN_LINK_PATTERN.matcher(goal).replaceAll("$2");
    }
}
