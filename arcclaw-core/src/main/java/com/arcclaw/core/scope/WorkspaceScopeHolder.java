package com.arcclaw.core.scope;

import org.jboss.logging.Logger;

import java.lang.ScopedValue;
import java.util.concurrent.Callable;

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
    private static final Logger LOG = Logger.getLogger(WorkspaceScopeHolder.class);

    // Bridge for MicroProfile Context Propagation which doesn't support ScopedValue natively
    private static final ThreadLocal<String> PROPAGATION_BRIDGE = new ThreadLocal<>();

    private WorkspaceScopeHolder() {
        // utility class — no instantiation
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
     * Manual session management for tests or non-standard scenarios.
     * <p>
     * Prefer {@link #run(String, Runnable)} or {@link #call(String, Callable)}
     * whenever possible. This method uses the propagation bridge ({@code ThreadLocal}).
     */
    public static void set(String sessionId) {
        validate(sessionId);
        setBridge(sessionId);
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
        validate(sessionId);
        LOG.debugf("Binding session: %s on thread: %s", sessionId, Thread.currentThread());
        org.jboss.logging.MDC.put("sessionId", sessionId);
        try {
            ScopedValue.where(WORKSPACE_ID, sessionId).run(runnable);
        } finally {
            org.jboss.logging.MDC.remove("sessionId");
        }
    }

    public static <T> T call(String sessionId, Callable<T> callable) throws Exception {
        validate(sessionId);
        LOG.debugf("Binding session: %s on thread: %s", sessionId, Thread.currentThread());
        return ScopedValue.where(WORKSPACE_ID, sessionId).call(new ScopedValue.CallableOp<T, Exception>() {
            @Override
            public T call() throws Exception {
                return callable.call();
            }
        });
    }

    public static Runnable wrap(Runnable runnable) {
        String sessionId = getIfActive();
        if (sessionId == null) {
            return runnable;
        }
        return () -> run(sessionId, runnable);
    }

    public static <T> Callable<T> wrap(Callable<T> callable) {
        String sessionId = getIfActive();
        if (sessionId == null) {
            return callable;
        }
        return () -> call(sessionId, callable);
    }

    private static void validate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be null or blank");
        }
    }
}
