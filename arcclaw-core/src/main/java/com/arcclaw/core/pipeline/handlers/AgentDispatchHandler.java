package com.arcclaw.core.pipeline.handlers;

import java.util.List;
import java.util.concurrent.Callable;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ReplyNode;
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
import com.arcclaw.core.skill.SkillRegistry;
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

    private static final int DEFAULT_MAX_ITERATIONS = 5;

    @Inject
    AgentLoop agentLoop;

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    SkillRegistry skillRegistry;

    @Inject
    ChannelPluginRegistry channelPluginRegistry;

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
        String goal = (context.getGoal() != null && !context.getGoal().isBlank())
                ? context.getGoal()
                : (context.getMessage() != null ? context.getMessage().text() : "");

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

        // getChatId() can be null on some Telegram paths — fall back to channelId
        String chatId = context.getChatId();
        if (chatId == null || chatId.isBlank()) {
            chatId = context.getUser().channelId();
            log.debugf("[AgentDispatch] getChatId() was null — falling back to channelId=%s", chatId);
        }
        final String resolvedChatId = chatId;

        AgentResult result;
        try {
            String channel = context.getChannelType().name();
            Callable<AgentResult> op = () -> agentLoop.run(
                    sessionId,
                    role,
                    goal,
                    reasoningEngine,
                    resolvedMaxIterations,
                    context
                );
            result = WorkspaceContext.<AgentResult>run(
                sessionId,
                context.getUser().platformId(),
                resolvedChatId,
                finalLevel,
                role,
                channel,
                op
            );
        } catch (BudgetExceededException | BudgetWarningException e) {
            throw e;   // propagate typed — caller maps to 429
        } catch (Exception e) {
            log.errorf("[AgentDispatch] Error processing command '%s': %s", goal, e.getMessage());
            log.debug("Full agent dispatch failure stack trace:", e);
            
            channelPluginRegistry.find(context.getChannelType())
                .map(ChannelPlugin::sender)
                .ifPresentOrElse(
                    s -> s.sendRaw(context.getUser(), "❌ An internal error occurred. Please try again."),
                    () -> log.warnf("AgentDispatchHandler: no ChannelPlugin for type %s — error reply dropped", context.getChannelType())
                );
            return HandleResult.STOP;  // stop pipeline gracefully — do not throw
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
                () -> log.warnf("AgentDispatchHandler: no ChannelPlugin for type %s — result reply dropped", context.getChannelType())
            );

        return HandleResult.STOP;
    }
}
