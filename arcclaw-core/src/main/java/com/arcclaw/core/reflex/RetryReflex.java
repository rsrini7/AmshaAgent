package com.arcclaw.core.reflex;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.loop.CheckpointResumeCoordinator;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;

/**
 * Resurrects the last archived stale approval by treating the /retry command
 * as if the user re-typed the original goal.
 */
@ApplicationScoped
public class RetryReflex implements AgentReflex {

    @Inject
    StatefulAgentStore agentStore;

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean matches(String input) {
        return input != null && input.trim().equalsIgnoreCase("/retry");
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String message, MessageContext context) {
        Optional<AgentCheckpoint> latestOpt = agentStore.findLatestByStatus(sessionId, CheckpointStatus.ARCHIVED);
        if (latestOpt.isEmpty()) {
            return ReflexResult.respond("❌ No previously cancelled operation found to retry.", context);
        }

        AgentCheckpoint checkpoint = latestOpt.get();
        if (checkpoint.getOriginalGoal() != null && 
            checkpoint.getFailReason() != null && 
            checkpoint.getFailReason().equals(CheckpointResumeCoordinator.FAIL_REASON_STALE_DISCARD)) {
            
            // Forward the original goal instead of "/retry", 
            // the LLM will see this as the user re-typing their request.
            context.setGoal(checkpoint.getOriginalGoal());
            return new ReflexResult(Optional.empty(), checkpoint.getOriginalGoal());
        }
        
        return ReflexResult.respond("❌ The previous operation cannot be retried (not a discarded stale approval).", context);
    }
}
