package com.amsha.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.message.UserMessages;
import com.amsha.core.pipeline.MessageContext;

import org.jboss.logging.Logger;

@ApplicationScoped
@ReflexPriority(500)
public class HaltReflex implements AgentReflex {

    @Inject
    Logger log;

    @Inject
    WorkspaceManager workspaceManager;

    @Override
    public boolean matches(String input) {
        if (input == null) {
            return false;
        }
        String trimmed = input.trim();
        return trimmed.equalsIgnoreCase("/halt") || trimmed.equalsIgnoreCase("/stop");
    }

    @Override
    public String auditDetail(String sessionId, String input) {
        return "reflex=HaltReflex session=" + sessionId;
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        log.errorf("[HaltReflex] SYSTEM HALT issued by user=%s for session=%s", userId, sessionId);
        workspaceManager.terminateSession(sessionId);
        return ReflexResult.respond(UserMessages.SESSION_HALTED, context);
    }
}
