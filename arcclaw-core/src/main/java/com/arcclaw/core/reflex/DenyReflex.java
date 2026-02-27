package com.arcclaw.core.reflex;

import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class DenyReflex implements AgentReflex {

    @Inject
    Logger log;

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Override
    public boolean matches(String input) {
        return input != null && (input.trim().equalsIgnoreCase("/deny") || input.trim().equalsIgnoreCase("deny"));
    }

    @Override
    public String execute(String sessionId, String userId, String input) {
        try {
            return WorkspaceScopeHolder.call(sessionId, () -> {
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

                log.infof("[DenyReflex] User denied pending tool execution for session: %s", sessionId);
                AgentCheckpoint cp = statefulAgentStore.load(sessionId, activeRole).orElseThrow();
                cp.setStatus(CheckpointStatus.FAILED);
                cp.setFailReason("Human denied the tool execution.");
                statefulAgentStore.save(cp);
                return "🚫 Execution denied. The agent's task has been terminated.";
            });
        } catch (Exception e) {
            log.errorf("Failed to process denial for session %s: %s", sessionId, e.getMessage(), e);
            return "❌ **Denial Error**: An internal fault occurred while terminating the task.";
        }
    }
}
