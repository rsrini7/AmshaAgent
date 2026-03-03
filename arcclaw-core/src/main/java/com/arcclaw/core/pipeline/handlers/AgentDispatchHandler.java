package com.arcclaw.core.pipeline.handlers;

import com.arcclaw.core.agent.AgentExecutionContext;
import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.AgentState;
import com.arcclaw.core.agent.ReasoningEngine;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.util.AgentErrorMessageUtil;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

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

        AgentExecutionContext execContext = new AgentExecutionContext(
                sessionId,
                context.getUser().platformId(),
                context.getChatId(),
                role,
                goal,
                AutonomyLevel.SUPERVISED, // Channel users default to supervised for now
                5 // Default max iterations
        );

        AgentState initialState = context.getInitialState().orElseGet(() -> new AgentState.InProgress(List.of()));

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
        String output = formatResult(result);
        context.getSender().sendRaw(context.getUser(), output);

        return HandleResult.STOP;
    }

    private String formatResult(AgentResult result) {
        String reply = switch (result) {
            case AgentResult.Finished f       -> f.answer();
            case AgentResult.AwaitingApproval a -> "⏸ Approval needed for tool: " + a.toolName() 
                                                + "\nReply /approve or /deny";
            case AgentResult.LoopExceeded _   -> "⏱ I ran out of steps. Try rephrasing your request.";
            case AgentResult.Failed f         -> "❌ " + AgentErrorMessageUtil.sanitizeErrorMessage(f.reason());
        };

        // Guard: never send empty/null to Telegram
        if (reply == null || reply.isBlank()) {
            reply = "✅ Done.";
        }
        return reply;
    }
}
