package com.arcclaw.core.pipeline.handlers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.StatefulAgentStore;
import org.jboss.logging.Logger;

/**
 * Resolves the session ID and autonomy level for the incoming message.
 * <p>
 * Priority 40: Runs before intent classification (50) and dispatch (70).
 */
@ApplicationScoped
public class SessionResolutionHandler implements MessageHandler {
    private static final Logger log = Logger.getLogger(SessionResolutionHandler.class);

    private final StatefulAgentStore agentStore;
    private final SessionHeartbeatTracker heartbeatTracker;

    protected SessionResolutionHandler() {
        this.agentStore = null;
        this.heartbeatTracker = null;
    }

    @Inject
    public SessionResolutionHandler(StatefulAgentStore agentStore, SessionHeartbeatTracker heartbeatTracker) {
        this.agentStore = agentStore;
        this.heartbeatTracker = heartbeatTracker;
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        // Chat ID is the primary key for session lookup in channel mode
        String chatId = context.getChatId();
        String userId = context.getUser().platformId();
        
        String sessionId = UUID.nameUUIDFromBytes((chatId + ":" + userId)
                .getBytes(StandardCharsets.UTF_8)).toString();
                
        context.setSessionId(sessionId);
        log.debugf("Resolved session %s for chat %s", sessionId, chatId);

        // Load stored autonomy level
        AutonomyLevel level = heartbeatTracker.getAutonomyLevel(sessionId);
        if (level == null) {
            level = agentStore.loadLatestAutonomyForSession(sessionId).orElse(null);
        }

        if (level == null) {
            log.warnf("No stored autonomy found for session %s (first message?), defaulting to SUPERVISED", sessionId);
            level = AutonomyLevel.SUPERVISED;
        }

        context.setAutonomyLevel(level);
        log.debugf("Set autonomy level %s for session %s", level, sessionId);
        
        return HandleResult.CONTINUE;
    }
}
