package com.arcclaw.core.scope;

import java.util.concurrent.Callable;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.security.AutonomyLevel;

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


    public static AutonomyLevel autonomy() {
        return AUTONOMY.isBound() && AUTONOMY.get() != null ? AUTONOMY.get() : AutonomyLevel.SUPERVISED;
    }


    public static String userId() {
        return USER_ID.isBound() && USER_ID.get() != null ? USER_ID.get() : "";
    }


    public static String chatId() {
        return CHAT_ID.isBound() && CHAT_ID.get() != null ? CHAT_ID.get() : "";
    }


    public static String agentRole() {
        return AGENT_ROLE.isBound() && AGENT_ROLE.get() != null ? AGENT_ROLE.get() : "";
    }


    // Accessor with safe default — TELEGRAM is correct for all existing callers
    public static String channelType() {
        return CHANNEL_TYPE.isBound() && CHANNEL_TYPE.get() != null
            ? CHANNEL_TYPE.get()
            : "TELEGRAM";
    }
}
