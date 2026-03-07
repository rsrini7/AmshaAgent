package com.arcclaw.core.pipeline.handlers;

import org.jboss.logging.Logger;

import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Dispatches the enriched request to the Agent State Machine.
 */
@ApplicationScoped
public class AgentDispatchHandler implements MessageHandler {
    private static final Logger log = Logger.getLogger(AgentDispatchHandler.class);

    @Inject 
    AgentLoop agentLoop;
    
    @Inject 
    ReasoningEngine reasoningEngine;

    @Override
    public int priority() {
        return 70;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        String sessionId = context.getSessionId()
                .orElseThrow(() -> new IllegalStateException("Session ID is required for agent dispatch"));
        String role = context.getAgentRole()
                .orElse("coordinator");
        String goal = context.getMessage() != null ? context.getMessage().text() : "";

        log.infof("Dispatching message for session %s to agent %s", sessionId, role);

        AgentResult result = null;
        try {
            result = WorkspaceContext.run(
                sessionId,
                context.getUser().platformId(),
                context.getChatId(),
                AutonomyLevel.SUPERVISED,
                role,
                () -> agentLoop.run(
                    sessionId,
                    role,
                    goal,
                    reasoningEngine,
                    5
                )
            );
        } catch (Exception e) {
            log.errorf("[AgentDispatch] Error processing command '%s': %s", goal, e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }

        // Render result back to user (placeholder until ReplyRenderer is fully integrated)
        String output = com.arcclaw.core.util.CronResultFormatter.format(result);
        context.getSender().sendRaw(context.getUser(), output);

        return HandleResult.STOP;
    }
}
