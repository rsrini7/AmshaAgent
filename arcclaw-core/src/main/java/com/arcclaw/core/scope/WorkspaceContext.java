package com.arcclaw.core.scope;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

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
    public static final ScopedValue<String> SUGGESTED_CRON_SOURCE = ScopedValue.newInstance();
    public static final ScopedValue<Boolean> IS_WRITE_INTENT = ScopedValue.newInstance();
    public static final ScopedValue<Boolean> IS_COMPACT = ScopedValue.newInstance();
    public static final ScopedValue<String> RAW_USER_GOAL = ScopedValue.newInstance();
    public static final ScopedValue<String> CHANNEL_PLATFORM_ID = ScopedValue.newInstance();


    private WorkspaceContext() {
        // utility class
    }



    public static <T> T run(String sessionId, String userId, String chatId,
            AutonomyLevel autonomy, String role, Callable<T> task) throws Exception {
        // WorkspaceScopeHolder.call() binds WORKSPACE_ID first - required for
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
            AutonomyLevel autonomy, String role, String channelType, String platformId, Callable<T> task) throws Exception {
        return WorkspaceScopeHolder.call(sessionId, userId, chatId, () ->
            ScopedValue.where(SESSION_ID, sessionId)
                .where(USER_ID, userId)
                .where(CHAT_ID, chatId)
                .where(AUTONOMY, autonomy)
                .where(AGENT_ROLE, role)
                .where(CHANNEL_TYPE, channelType)
                .where(CHANNEL_PLATFORM_ID, platformId)
                .call(() -> task.call())
        );
    }



    public static <T> T run(String sessionId, String userId, String chatId,
            AutonomyLevel autonomy, String role, String channelType, Callable<T> task) throws Exception {
        return run(sessionId, userId, chatId, autonomy, role, channelType, null, task);
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
            AutonomyLevel autonomy, String role, String channelType, String platformId, Runnable task) {
        try {
            run(sessionId, userId, chatId, autonomy, role, channelType, platformId, () -> {
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
            AutonomyLevel autonomy, String role, String channelType, Runnable task) {
        run(sessionId, userId, chatId, autonomy, role, channelType, null, task);
    }



    public static <T> T runWithFullAutonomy(String sessionId, String userId,
            String chatId, String role, Callable<T> task) throws Exception {
        return run(sessionId, userId, chatId, AutonomyLevel.FULL, role, task);
    }


    public static <T> T runWithFullAutonomy(String sessionId, String userId,
            String chatId, String role, String channelType, Callable<T> task) throws Exception {
        return run(sessionId, userId, chatId, AutonomyLevel.FULL, role, channelType, task);
    }


    public static <T> T runWithFullAutonomy(String sessionId, String userId,
            String chatId, String role, String channelType, String platformId, Callable<T> task) throws Exception {
        return run(sessionId, userId, chatId, AutonomyLevel.FULL, role, channelType, platformId, task);
    }



    public static void runWithFullAutonomy(String sessionId, String userId,
            String chatId, String role, Runnable task) {
        run(sessionId, userId, chatId, AutonomyLevel.FULL, role, task);
    }


    public static void runWithFullAutonomy(String sessionId, String userId,
            String chatId, String role, String channelType, Runnable task) {
        runWithFullAutonomy(sessionId, userId, chatId, role, channelType, null, task);
    }


    public static void runWithFullAutonomy(String sessionId, String userId,
            String chatId, String role, String channelType, String platformId, Runnable task) {
        try {
            run(sessionId, userId, chatId, AutonomyLevel.FULL, role, channelType, platformId, () -> {
                task.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    // Currently not used anywhere, but need to check the usecase and use it during scenario based testing....
    public static AutonomyLevel requireAutonomy() {
        if (!AUTONOMY.isBound() || AUTONOMY.get() == null) {
            throw new IllegalStateException("WorkspaceContext AUTONOMY is unbound");
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


    public static Optional<String> channelType() {
        if (CHANNEL_TYPE.isBound() && CHANNEL_TYPE.get() != null) {
            return Optional.of(CHANNEL_TYPE.get());
        }
        return Optional.empty();
    }

    public static String channelPlatformId() {
        return CHANNEL_PLATFORM_ID.isBound() ? CHANNEL_PLATFORM_ID.get() : null;
    }

    public static String suggestedCron() {
        return SUGGESTED_CRON.isBound() ? SUGGESTED_CRON.get() : null;
    }

    public static String suggestedNextFireTime() {
        return SUGGESTED_NEXT_FIRE_TIME.isBound() ? SUGGESTED_NEXT_FIRE_TIME.get() : null;
    }

    /**
     * Returns {@code "UTC"} when the SUGGESTED_CRON was pre-resolved to UTC by
     * Java-side arithmetic (CommandDispatchHandler / ChatCommand). This signals
     * {@code ScheduleTaskTool} to override the LLM-provided timezone to UTC,
     * preventing double timezone conversion.
     */
    public static String suggestedCronSource() {
        return SUGGESTED_CRON_SOURCE.isBound() ? SUGGESTED_CRON_SOURCE.get() : null;
    }

    /**
     * @return A map of all bound ScopedValues for persistence.
     */
    public static Map<String, Object> getData() {
        Map<String, Object> data = new HashMap<>();
        if (SUGGESTED_CRON.isBound()) data.put("SUGGESTED_CRON", SUGGESTED_CRON.get());
        if (SUGGESTED_NEXT_FIRE_TIME.isBound()) data.put("SUGGESTED_NEXT_FIRE_TIME", SUGGESTED_NEXT_FIRE_TIME.get());
        if (SUGGESTED_CRON_SOURCE.isBound()) data.put("SUGGESTED_CRON_SOURCE", SUGGESTED_CRON_SOURCE.get());
        if (IS_WRITE_INTENT.isBound()) data.put("IS_WRITE_INTENT", IS_WRITE_INTENT.get());
        if (IS_COMPACT.isBound()) data.put("IS_COMPACT", IS_COMPACT.get());
        return data;
    }

    /**
     * Returns the currently bound USER_ID from ScopedValue or returns "unknown".
     * Includes MDC fallback for compatibility with non-virtual threads/filters (§2.3).
     */
    public static String getUserIdOrUnknown() {
        try {
            if (USER_ID.isBound() && USER_ID.get() != null) {
                return USER_ID.get();
            }
            String mdcUser = (String) MDC.get(MdcKeys.USER_ID);
            return (mdcUser != null && !mdcUser.isBlank()) ? mdcUser : "unknown";
        } catch (Exception _ ) {
            return "unknown";
        }
    }
}
