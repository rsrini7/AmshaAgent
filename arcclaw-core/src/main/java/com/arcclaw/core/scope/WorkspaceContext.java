package com.arcclaw.core.scope;

import java.util.Optional;
import java.util.concurrent.Callable;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.util.MdcKeys;
import org.jboss.logging.MDC;

/**
 * Pure static utility backed by ScopedValue constants to hold Workspace Context.
 * Eliminates CDI singleton race conditions and secures context propagation.
 */
public final class WorkspaceContext {


    public static final ScopedValue<String> SESSION_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> USER_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> CHAT_ID = ScopedValue.newInstance();
    public static final ScopedValue<AutonomyLevel> AUTONOMY = ScopedValue.newInstance();
    public static final ScopedValue<String> AGENT_ROLE = ScopedValue.newInstance();
    public static final ScopedValue<String> CHANNEL_TYPE = ScopedValue.newInstance();
    public static final ScopedValue<String> SUGGESTED_CRON = ScopedValue.newInstance();
    public static final ScopedValue<String> SUGGESTED_NEXT_FIRE_TIME = ScopedValue.newInstance();


    private WorkspaceContext() {
        // utility class
    }


    public static <T> T run(String sessionId, String userId, String chatId,
            AutonomyLevel autonomy, String role, Callable<T> task) throws Exception {
        // WorkspaceScopeHolder.call() binds WORKSPACE_ID first — required for
        // WorkspaceScopeContext.isActive() to return true, which enables
        // @WorkspaceScoped CDI beans (e.g. SessionKnowledgeBase) to resolve.
        return WorkspaceScopeHolder.call(sessionId, userId, chatId, () ->
            ScopedValue.where(SESSION_ID, sessionId)
                .where(USER_ID, userId)
                .where(CHAT_ID, chatId)
                .where(AUTONOMY, autonomy)
                .where(AGENT_ROLE, role)
                .call(() -> task.call())
        );
    }


    public static <T> T run(String sessionId, String userId, String chatId,
            AutonomyLevel autonomy, String role, ChannelType channelType, Callable<T> task) throws Exception {
        return WorkspaceScopeHolder.call(sessionId, userId, chatId, () ->
            ScopedValue.where(SESSION_ID, sessionId)
                .where(USER_ID, userId)
                .where(CHAT_ID, chatId)
                .where(AUTONOMY, autonomy)
                .where(AGENT_ROLE, role)
                .where(CHANNEL_TYPE, channelType.name())
                .call(() -> task.call())
        );
    }


    public static void run(String sessionId, String userId, String chatId,
            AutonomyLevel autonomy, String role, Runnable task) {
        try {
            run(sessionId, userId, chatId, autonomy, role, () -> {
                task.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public static void run(String sessionId, String userId, String chatId,
            AutonomyLevel autonomy, String role, ChannelType channelType, Runnable task) {
        try {
            run(sessionId, userId, chatId, autonomy, role, channelType, () -> {
                task.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public static <T> T runWithFullAutonomy(String sessionId, String userId,
            String chatId, String role, Callable<T> task) throws Exception {
        return run(sessionId, userId, chatId, AutonomyLevel.FULL, role, task);
    }


    public static void runWithFullAutonomy(String sessionId, String userId,
            String chatId, String role, Runnable task) {
        run(sessionId, userId, chatId, AutonomyLevel.FULL, role, task);
    }


    public static String requireSessionId() {
        if (!SESSION_ID.isBound() || SESSION_ID.get() == null || SESSION_ID.get().isBlank()) {
            throw new IllegalStateException("WorkspaceContext SESSION_ID is unbound");
        }
        return SESSION_ID.get();
    }

    // Currently not used anywhere, but need tocheck the usecase and use it during scenario based testing.... 
    public static AutonomyLevel requireAutonomy() {
        if (!AUTONOMY.isBound() || AUTONOMY.get() == null) {
            throw new IllegalStateException("WorkspaceContext AUTONOMY is unbound");
        }
        return AUTONOMY.get();
    }

    public static AutonomyLevel requireAutonomyOrSupervised() {
        if (!AUTONOMY.isBound() || AUTONOMY.get() == null) {
            return AutonomyLevel.SUPERVISED;
        }
        return AUTONOMY.get();
    }

    public static String resolveUserId() {
        try {
            if (USER_ID.isBound() && USER_ID.get() != null) {
                return USER_ID.get();
            }
            // Fallback for non-virtual threads (e.g. Filters) where ScopedValue is not yet bound.
            // Also supports test suites that populate MDC but not ScopedValues.
            String mdcUser = (String) MDC.get(MdcKeys.USER_ID);
            return mdcUser != null ? mdcUser : "";
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static String requireUserId() {
        if (!USER_ID.isBound() || USER_ID.get() == null || USER_ID.get().isBlank()) {
            throw new IllegalStateException("WorkspaceContext USER_ID is unbound");
        }
        return USER_ID.get();
    }

    public static String requireChatId() {
        if (!CHAT_ID.isBound() || CHAT_ID.get() == null || CHAT_ID.get().isBlank()) {
            throw new IllegalStateException("WorkspaceContext CHAT_ID is unbound");
        }
        return CHAT_ID.get();
    }

    public static String requireChatIdOrUserId() {
        if (!CHAT_ID.isBound() || CHAT_ID.get() == null) {
            return requireUserId();
        }
        return CHAT_ID.get();
    }
 
    public static String requireAgentRole() {
        if (!AGENT_ROLE.isBound() || AGENT_ROLE.get() == null || AGENT_ROLE.get().isBlank()) {
            throw new IllegalStateException("WorkspaceContext AGENT_ROLE is unbound");
        }
        return AGENT_ROLE.get();
    }


    public static Optional<String> channelType() {
        if (CHANNEL_TYPE.isBound() && CHANNEL_TYPE.get() != null) {
            return Optional.of(CHANNEL_TYPE.get());
        }
        return Optional.empty();
    }

    public static String requireChannelType() {
        return channelType().orElseThrow(() ->
            new IllegalStateException(
                "CHANNEL_TYPE not bound — violates Architecture_Invariants.md §1.4. " +
                "Ensure all channel entry points bind ChannelType via " +
                "WorkspaceContext.run(..., channelType, ...)."));
    }

    public static String suggestedCron() {
        return SUGGESTED_CRON.isBound() ? SUGGESTED_CRON.get() : null;
    }

    public static String suggestedNextFireTime() {
        return SUGGESTED_NEXT_FIRE_TIME.isBound() ? SUGGESTED_NEXT_FIRE_TIME.get() : null;
    }
}
