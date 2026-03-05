package com.arcclaw.core.agent;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * High-level runner that executes an {@link AgentState} transition.
 * 
 * <p>This class will be fully implemented in Tier 2B. 
 * For now, it serves as the architectural hook for the message pipeline.
 */
@ApplicationScoped
public class AgentStateMachineRunner {
    private static final Logger log = Logger.getLogger(AgentStateMachineRunner.class);

    public AgentResult run(AgentExecutionContext context, AgentState initialState) {
        log.infof("[STATE-MACHINE] Starting transition for session %s (state=%s)", 
                context.sessionId(), initialState.getClass().getSimpleName());
        
        // TODO: Tier 2B — implement the actual state machine iteration logic
        return AgentResult.failed("StateMachine logic not yet wired in Tier 2A", 
                AgentResult.FailureCategory.SYSTEM_ERROR, initialState.steps());
    }
}
