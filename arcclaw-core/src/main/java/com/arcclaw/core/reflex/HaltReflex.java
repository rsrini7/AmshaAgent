package com.arcclaw.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
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
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        log.errorf("[HaltReflex] SYSTEM HALT issued by user=%s for session=%s", userId, sessionId);
        workspaceManager.terminateSession(sessionId);
        return ReflexResult.respond("🛑 System Halted. Workspace terminated.", context);
    }
}
