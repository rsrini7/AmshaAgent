package com.amsha.core.pipeline.handlers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.channel.api.ChannelType;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.pipeline.HandleResult;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.pipeline.MessageHandler;
import com.amsha.core.pipeline.SessionContext;
import com.amsha.core.pipeline.SessionIdResolver;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.state.StatefulAgentStore;

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
    public SessionResolutionHandler(
            StatefulAgentStore agentStore,
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

        // Load stored autonomy level - three-tier resolution:
        // 1. In-memory heartbeat (fastest, lost on restart)
        // 2. Latest checkpoint in DB (survives restarts, set by AgentLoop per turn)
        // 3. Default SUPERVISED (first-ever Telegram message or post-restart cold start)
        AutonomyLevel level = heartbeatTracker.getAutonomyLevel(sessionId);
        if (level == null) {
            level = agentStore.loadLatestAutonomyForSession(sessionId).orElse(null);
        }

        if (level == null) {
            // Expected only on the very first Telegram message for a session,
            // or after a server restart before any checkpoint has been written.
            // The level is written back to heartbeat below, so this fires at most
            // once per session per JVM lifetime.
            log.debugf(
                    "No stored autonomy found for session %s (first message or post-restart), defaulting to SUPERVISED",
                    sessionId);
            level = AutonomyLevel.SUPERVISED;
        }

        // Telegram sessions never go through AgentSessionService.createSession(),
        // so their autonomy level is never pre-populated.
        // We cap FULL→SUPERVISED for Telegram to prevent accidental
        // self-promotion via the "I am an AI" fallback.
        if (level == AutonomyLevel.FULL
                && ChannelType.TELEGRAM.equals(context.getUser().channel())) {
            log.debugf("Capping FULL→SUPERVISED for Telegram session %s", sessionId);
            level = AutonomyLevel.SUPERVISED;
        }

        // Write the resolved level back into the heartbeat cache.
        // This ensures the WARN above fires at most once per session per JVM lifetime
        // rather than on every message. Telegram sessions never go through
        // AgentSessionService.createSession(), so heartbeat is not pre-populated.
        heartbeatTracker.recordHeartbeat(sessionId, level);

        context.setAutonomyLevel(level);
        log.debugf("Set autonomy level %s for session %s", level, sessionId);

        SessionContext sc = new SessionContext(sessionId, context.getUser(), level, context.isResumeSignal());
        context.setSessionContext(sc);

        return HandleResult.CONTINUE;
    }
}
