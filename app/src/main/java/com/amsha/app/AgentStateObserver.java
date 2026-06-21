package com.amsha.app;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import com.amsha.core.agent.event.AgentStateChangedEvent;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.util.AgentGuards;

import org.jboss.logging.Logger;

@ApplicationScoped
public class AgentStateObserver implements AgentObserver {

    private static final Logger log = Logger.getLogger(AgentStateObserver.class);

    @Inject
    Event<AgentStateChangedEvent> stateChangedEvent;

    @Override
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
        // No-op: AgentStateObserver only cares about loop completion events
    }

    @Override
    public void onLoopComplete(String sessionId, String userId, String agentRole, String goalHash, AgentResult result) {
        if (AgentGuards.isShuttingDown()) {
            log.debugf("Container shutting down - skipping onLoopComplete for session %s", sessionId);
            return;
        }

        String status =
                switch (result) {
                    case AgentResult.Finished _ -> "idle";
                    case AgentResult.StructuredFinished _ -> "idle";
                    case AgentResult.AwaitingApproval _ -> "awaiting_approval";
                    case AgentResult.AwaitingRetry _ -> "awaiting_retry";
                    case AgentResult.LoopExceeded _ -> "idle";
                    case AgentResult.Failed _ -> "idle";
                };
        stateChangedEvent.fire(new AgentStateChangedEvent(
                sessionId,
                agentRole, // agentName
                status,
                "" // currentTask cleared on complete
                ));
    }
}
