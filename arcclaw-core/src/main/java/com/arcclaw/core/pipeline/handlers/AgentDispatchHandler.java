package com.arcclaw.core.pipeline.handlers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.pipeline.SessionContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.skill.SkillRegistry;
import com.arcclaw.core.util.CronResultFormatter;
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

    private static final String SKILL_PREFIX = "skill:";
    private static final int DEFAULT_MAX_ITERATIONS = 5;

    @Inject
    AgentLoop agentLoop;

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    SkillRegistry skillRegistry;

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
        String goal = context.getMessage() != null ? context.getMessage().text() : "";

        // Resolve maxIterations: use the skill's configured value when routing to a skill,
        // otherwise fall back to the default of 5.
        int maxIterations = DEFAULT_MAX_ITERATIONS;
        if (role.startsWith(SKILL_PREFIX)) {
            String skillName = role.substring(SKILL_PREFIX.length());
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
        AgentResult result;
        try {
            result = WorkspaceContext.run(
                sessionId,
                context.getUser().platformId(),
                context.getChatId(),
                finalLevel,
                role,
                () -> agentLoop.run(
                    sessionId,
                    role,
                    goal,
                    reasoningEngine,
                    resolvedMaxIterations,
                    context
                )

            );
        } catch (Exception e) {
            log.errorf("[AgentDispatch] Error processing command '%s': %s", goal, e.getMessage());
            log.debug("Full agent dispatch failure stack trace:", e);
            throw new RuntimeException(e.getMessage(), e);
        }

        String output = CronResultFormatter.format(result);
        context.getSender().sendRaw(context.getUser(), output);

        return HandleResult.STOP;
    }
}
