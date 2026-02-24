package com.arcclaw.core.reflex;

import com.arcclaw.core.lifecycle.WorkspaceManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class HaltReflex implements AgentReflex {

    @Inject
    Logger log;

    @Inject
    WorkspaceManager workspaceManager;

    @Override
    public boolean matches(String input) {
        return input != null && input.trim().equalsIgnoreCase("/halt");
    }

    @Override
    public String execute(String sessionId, String userId) {
        log.errorf("[HaltReflex] SYSTEM HALT issued by user=%s for session=%s", userId, sessionId);
        workspaceManager.terminateSession(sessionId);
        return "🛑 System Halted. Workspace terminated.";
    }
}
