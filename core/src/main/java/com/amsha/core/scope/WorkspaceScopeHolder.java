package com.amsha.core.scope;

import java.util.concurrent.Callable;

import com.amsha.channel.api.ChannelType;
import com.amsha.core.util.MdcKeys;
import com.amsha.security.api.AutonomyLevel;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;

/**
 * Scoped value holder for the current workspace session ID.
 * <p>
 * REST filters and WebSocket lifecycle handlers call {@link #run(String, Runnable)}
 * or {@link #call(String, Callable)} at entry points. The {@link WorkspaceScopeContext}
 * reads the current session ID via {@link #getIfActive()} to resolve the correct
 * per-session bean store.
 *
 * @see WorkspaceScopeContext
 */
public final class WorkspaceScopeHolder {

    private static final ScopedValue<String> WORKSPACE_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> USER_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> CHAT_ID = ScopedValue.newInstance();
    private static final Logger LOG = Logger.getLogger(WorkspaceScopeHolder.class);

    // Bridge for MicroProfile Context Propagation which doesn't support ScopedValue natively
    private static final ThreadLocal<String> PROPAGATION_BRIDGE = new ThreadLocal<>();

    private WorkspaceScopeHolder() {
        // utility class - no instantiation
    }

    public static boolean isActive() {
        return WORKSPACE_ID.isBound() || PROPAGATION_BRIDGE.get() != null;
    }

    public static String getIfActive() {
        if (WORKSPACE_ID.isBound()) {
            return WORKSPACE_ID.get();
        }
        return PROPAGATION_BRIDGE.get();
    }

    public static String getRequired() {
        String sessionId = getIfActive();
        if (sessionId == null) {
            throw new IllegalStateException("No active workspace session");
        }
        return sessionId;
    }

    /**
     * Clear the current session ID from the propagation bridge.
     */
    public static void clear() {
        setBridge(null);
    }

    /**
     * Internal method for MicroProfile Context Propagation.
     * Do not use directly in application code.
     */
    static void setBridge(String sessionId) {
        if (sessionId == null) {
            PROPAGATION_BRIDGE.remove();
        } else {
            PROPAGATION_BRIDGE.set(sessionId);
        }
    }

    public static void run(String sessionId, Runnable runnable) {
        run(sessionId, null, null, runnable);
    }

    public static void run(String sessionId, String userId, String chatId, Runnable runnable) {
        validate(sessionId);
        if (userId != null || chatId != null) {
            LOG.debugf(
                    "Binding session: %s [user=%s, chat=%s] on thread: %s",
                    sessionId, userId, chatId, Thread.currentThread());
        }

        MDC.put(MdcKeys.SESSION_ID, sessionId);
        if (userId != null) MDC.put(MdcKeys.USER_ID, userId);
        if (chatId != null) MDC.put(MdcKeys.CHAT_ID, chatId);

        var container = Arc.container();
        ManagedContext requestContext = container != null ? container.requestContext() : null;
        boolean wasActive = requestContext != null && requestContext.isActive();
        if (requestContext != null && !wasActive) requestContext.activate();

        try {
            var scope = ScopedValue.where(WORKSPACE_ID, sessionId);
            if (userId != null) {
                scope = scope.where(USER_ID, userId);
            }
            if (chatId != null) {
                scope = scope.where(CHAT_ID, chatId);
            }
            scope.run(runnable);
        } finally {
            if (requestContext != null && !wasActive) requestContext.deactivate();
            MDC.remove(MdcKeys.SESSION_ID);
            MDC.remove(MdcKeys.USER_ID);
            MDC.remove(MdcKeys.CHAT_ID);
        }
    }

    public static <T> T call(String sessionId, Callable<T> callable) throws Exception {
        return call(sessionId, null, null, callable);
    }

    public static <T> T call(String sessionId, String userId, String chatId, Callable<T> callable) throws Exception {
        validate(sessionId);
        if (userId != null || chatId != null) {
            LOG.debugf(
                    "Binding session: %s [user=%s, chat=%s] on thread: %s",
                    sessionId, userId, chatId, Thread.currentThread());
        }

        MDC.put(MdcKeys.SESSION_ID, sessionId);
        if (userId != null) MDC.put(MdcKeys.USER_ID, userId);
        if (chatId != null) MDC.put(MdcKeys.CHAT_ID, chatId);

        var container = Arc.container();
        ManagedContext requestContext = container != null ? container.requestContext() : null;
        boolean wasActive = requestContext != null && requestContext.isActive();
        if (requestContext != null && !wasActive) requestContext.activate();

        try {
            var scope = ScopedValue.where(WORKSPACE_ID, sessionId);
            if (userId != null) {
                scope = scope.where(USER_ID, userId);
            }
            if (chatId != null) {
                scope = scope.where(CHAT_ID, chatId);
            }

            return scope.call(() -> callable.call());
        } finally {
            if (requestContext != null && !wasActive) requestContext.deactivate();
            MDC.remove(MdcKeys.SESSION_ID);
            MDC.remove(MdcKeys.USER_ID);
            MDC.remove(MdcKeys.CHAT_ID);
        }
    }

    public static Runnable wrap(Runnable runnable) {
        String sessionId = getIfActive();
        if (sessionId == null) {
            return runnable;
        }

        // Capture context from current thread
        String userId = WorkspaceContext.USER_ID.isBound() ? WorkspaceContext.USER_ID.get() : null;
        if (userId == null) userId = USER_ID.isBound() ? USER_ID.get() : null;
        final String finalUserId = userId;

        String chatId = WorkspaceContext.CHAT_ID.isBound() ? WorkspaceContext.CHAT_ID.get() : null;
        if (chatId == null) chatId = CHAT_ID.isBound() ? CHAT_ID.get() : null;
        final String finalChatId = chatId;

        AutonomyLevel autonomy = WorkspaceContext.AUTONOMY.isBound() ? WorkspaceContext.AUTONOMY.get() : null;
        String agentRole = WorkspaceContext.AGENT_ROLE.isBound() ? WorkspaceContext.AGENT_ROLE.get() : null;
        String channelType = WorkspaceContext.CHANNEL_TYPE.isBound() ? WorkspaceContext.CHANNEL_TYPE.get() : null;
        String platformId =
                WorkspaceContext.CHANNEL_PLATFORM_ID.isBound() ? WorkspaceContext.CHANNEL_PLATFORM_ID.get() : null;
        String activeWorkspaceId = WorkspaceContext.activeWorkspaceId().orElse(null);

        return () -> WorkspaceContext.runWithActiveWorkspace(activeWorkspaceId, () -> {
            if (channelType != null) {
                WorkspaceContext.run(
                        sessionId, finalUserId, finalChatId, autonomy, agentRole, channelType, platformId, runnable);
            } else if (autonomy != null && agentRole != null) {
                WorkspaceContext.run(sessionId, finalUserId, finalChatId, autonomy, agentRole, runnable);
            } else {
                run(sessionId, finalUserId, finalChatId, runnable);
            }
        });
    }

    public static <T> Callable<T> wrap(Callable<T> callable) {
        String sessionId = getIfActive();
        if (sessionId == null) {
            return callable;
        }

        // Capture context from current thread
        String userId = WorkspaceContext.USER_ID.isBound() ? WorkspaceContext.USER_ID.get() : null;
        if (userId == null) userId = USER_ID.isBound() ? USER_ID.get() : null;
        final String finalUserId = userId;

        String chatId = WorkspaceContext.CHAT_ID.isBound() ? WorkspaceContext.CHAT_ID.get() : null;
        if (chatId == null) chatId = CHAT_ID.isBound() ? CHAT_ID.get() : null;
        final String finalChatId = chatId;

        AutonomyLevel autonomy = WorkspaceContext.AUTONOMY.isBound() ? WorkspaceContext.AUTONOMY.get() : null;
        String agentRole = WorkspaceContext.AGENT_ROLE.isBound() ? WorkspaceContext.AGENT_ROLE.get() : null;
        String channelType = WorkspaceContext.CHANNEL_TYPE.isBound() ? WorkspaceContext.CHANNEL_TYPE.get() : null;
        String platformId =
                WorkspaceContext.CHANNEL_PLATFORM_ID.isBound() ? WorkspaceContext.CHANNEL_PLATFORM_ID.get() : null;
        String activeWorkspaceId = WorkspaceContext.activeWorkspaceId().orElse(null);

        return () -> WorkspaceContext.callWithActiveWorkspace(activeWorkspaceId, () -> {
            if (channelType != null) {
                // M1 Fix: Validate channel type before running, but don't throw to avoid crashing ongoing sessions
                // Instead, default to TELEGRAM and log a warning if unrecognized
                String validatedChannel = ChannelType.fromString(channelType)
                        .map(ChannelType::name)
                        .orElseGet(() -> {
                            Logger.getLogger(WorkspaceScopeHolder.class)
                                    .warnf(
                                            "Unrecognized channel type '%s' requested for session %s, defaulting to UNKNOWN",
                                            channelType, sessionId);
                            return "UNKNOWN";
                        });
                return WorkspaceContext.run(
                        sessionId,
                        finalUserId,
                        finalChatId,
                        autonomy,
                        agentRole,
                        validatedChannel,
                        platformId,
                        callable);
            } else if (autonomy != null && agentRole != null) {
                return WorkspaceContext.run(sessionId, finalUserId, finalChatId, autonomy, agentRole, callable);
            } else {
                return call(sessionId, finalUserId, finalChatId, callable);
            }
        });
    }

    private static void validate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be null or blank");
        }
    }

    /**
     * Safely retrieves the user ID from ScopedValue or returns "unknown".
     * Follows the pattern in WorkspaceContext.userId().
     */
    public static String getUserIdOrUnknown() {
        return USER_ID.isBound() && USER_ID.get() != null ? USER_ID.get() : "unknown";
    }
}
