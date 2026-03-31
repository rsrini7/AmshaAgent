package com.arcclaw.app;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.event.AgentStateChangedEvent;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.observability.AgentObserver;

@ApplicationScoped
public class AgentStateObserver implements AgentObserver {

    @Inject Event<AgentStateChangedEvent> stateChangedEvent;

    @Override
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {}

    @Override
    public void onLoopComplete(String sessionId, String userId,
            String agentRole, String goalHash, AgentResult result) {
        String status = switch (result) {
            case AgentResult.Finished _         -> "idle";
            case AgentResult.AwaitingApproval _ -> "awaiting_approval";
            case AgentResult.LoopExceeded _     -> "idle";
            case AgentResult.Failed _           -> "idle";
        };
        stateChangedEvent.fire(new AgentStateChangedEvent(
            sessionId,
            agentRole,  // agentName
            status,
            ""          // currentTask cleared on complete
        ));
    }
}