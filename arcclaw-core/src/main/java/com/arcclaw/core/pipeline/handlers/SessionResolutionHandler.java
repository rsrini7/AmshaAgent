package com.arcclaw.core.pipeline.handlers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.pipeline.SessionContext;
import com.arcclaw.core.pipeline.SessionIdResolver;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.StatefulAgentStore;
import org.jboss.logging.Logger;

/**
 * Resolves the session ID and autonomy level for the incoming message.
 * <p>
 * Priority 40: Runs before intent classification (50) and dispatch (70).
 * <p>
 * Session ID is resolved via {@link SessionIdResolver}, which in production
 * delegates to the persistent {@code SessionRegistry} (UUID v4, no MD5).
 */
@ApplicationScoped
public class SessionResolutionHandler implements MessageHandler {
    private static final Logger log = Logger.getLogger(SessionResolutionHandler.class);

    private final StatefulAgentStore agentStore;
    private final SessionHeartbeatTracker heartbeatTracker;
    private final SessionIdResolver sessionIdResolver;

    protected SessionResolutionHandler() {
        this.agentStore = null;
        this.heartbeatTracker = null;
        this.sessionIdResolver = null;
    }

    @Inject
    public SessionResolutionHandler(StatefulAgentStore agentStore,
                                    SessionHeartbeatTracker heartbeatTracker,
                                    SessionIdResolver sessionIdResolver) {
        this.agentStore = agentStore;
        this.heartbeatTracker = heartbeatTracker;
        this.sessionIdResolver = sessionIdResolver;
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        String channelId = context.getUser().channelId();
        String userId = context.getUser().platformId();
        String channelType = context.getUser().channel().name();

        String sessionId = sessionIdResolver.resolveOrCreate(channelType, channelId, userId);

        context.setSessionId(sessionId);
        log.debugf("Resolved session %s for [%s/%s/%s]", sessionId, channelType, channelId, userId);

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

        SessionContext sc = new SessionContext(sessionId, context.getUser(), level, context.isResumeSignal());
        context.setSessionContext(sc);

        return HandleResult.CONTINUE;
    }
}
