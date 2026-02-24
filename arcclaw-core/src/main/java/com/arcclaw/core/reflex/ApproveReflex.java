package com.arcclaw.core.reflex;

import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.ReasoningEngine;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.DevSecretSeeder;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ApproveReflex implements AgentReflex {

    @Inject
    Logger log;

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    WorkspaceContext workspaceContext;

    @Inject
    Instance<DevSecretSeeder> devSeeder;

    @Inject
    AgentLoop agentLoop;

    @Inject
    ReasoningEngine reasoningEngine;

    @Override
    public boolean matches(String input) {
        return input != null && (input.trim().equalsIgnoreCase("/approve") || input.trim().equalsIgnoreCase("approve"));
    }

    @Override
    public String execute(String sessionId, String userId) {
        WorkspaceScopeHolder.set(sessionId);
        try {
            String activeRole = null;
            for (String role : List.of("coordinator", "coder", "researcher", "debugger")) {
                Optional<AgentCheckpoint> cpOpt = statefulAgentStore.load(sessionId, role);
                if (cpOpt.isPresent() && cpOpt.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                    activeRole = role;
                    break;
                }
            }

            if (activeRole == null) {
                return "No pending tool executions require approval for this session.";
            }

            log.infof("[ApproveReflex] User approved pending tool execution for session: %s", sessionId);
            workspaceContext.setAutonomyLevel(AutonomyLevel.FULL);

            if (!devSeeder.isUnsatisfied()) {
                devSeeder.get().seedDevSecrets();
            }

            AgentResult result = agentLoop.run(
                    sessionId, activeRole,
                    "Resuming task after approval",
                    reasoningEngine, 5
            );

            if (result.success()) {
                return result.finalAnswer();
            } else {
                return "❌ **Task Failed after approval**\n\nReason: " + result.terminationReason();
            }
        } finally {
            WorkspaceScopeHolder.clear();
        }
    }
}
