package com.arcclaw.core.reflex;

import com.arcclaw.core.governance.GovernanceLayer;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.StatefulAgentStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class StatusReflex implements AgentReflex {

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    GovernanceLayer governanceLayer;

    @Override
    public boolean matches(String input) {
        return input != null && input.trim().equalsIgnoreCase("/status");
    }

    @Override
    public String execute(String sessionId, String userId, String input) {
        String status = "NO_ACTIVE_STATE";
        for (String role : List.of("coordinator", "coder", "researcher", "debugger")) {
            Optional<AgentCheckpoint> cpOpt = statefulAgentStore.load(sessionId, role);
            if (cpOpt.isPresent()) {
                status = cpOpt.get().getStatus().name();
                if (status.equals("AWAITING_APPROVAL") || status.equals("IN_PROGRESS")) {
                    break;
                }
            }
        }

        double cost = governanceLayer.getSessionCost(sessionId);
        String shortId = sessionId.length() > 8 ? sessionId.substring(sessionId.length() - 8) : sessionId;

        return String.format("📊 Status: %s | Cost: $%.4f | Session: %s", status, cost, shortId);
    }
}
