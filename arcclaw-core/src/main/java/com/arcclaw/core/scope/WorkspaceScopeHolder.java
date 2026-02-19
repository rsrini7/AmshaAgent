package com.arcclaw.core.scope;

/**
 * Thread-local holder for the current workspace session ID.
 * <p>
 * REST filters and WebSocket lifecycle handlers call {@link #set(String)} on
 * entry
 * and {@link #clear()} on exit. The {@link WorkspaceScopeContext} reads the
 * current
 * session ID via {@link #get()} to resolve the correct per-session bean store.
 *
 * <h2>JDK 25 Migration — JEP 506 (Scoped Values)</h2>
 * 
 * <pre>{@code
 * // Current (JDK 21+): ThreadLocal-based
 * private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
 *
 * // Future (JDK 25 GA / JEP 506): ScopedValue-based
 * // ScopedValue is final in JDK 25 — no longer preview.
 * // private static final ScopedValue<String> CURRENT =
 * // ScopedValue.newInstance();
 * //
 * // Activation becomes:
 * // ScopedValue.where(CURRENT, sessionId).run(() -> { ... });
 * //
 * // Benefits:
 * // - Immutable within scope (no accidental overwrites)
 * // - Automatic cleanup (no try-finally needed)
 * // - Better structured concurrency integration
 * // - No memory leak risk from forgotten clear()
 * }</pre>
 *
 * @see WorkspaceScopeContext
 */
public final class WorkspaceScopeHolder {

    private static final InheritableThreadLocal<String> CURRENT_SESSION = new InheritableThreadLocal<>();

    private WorkspaceScopeHolder() {
        // utility class — no instantiation
    }

    /**
     * Bind a workspace session to the current thread.
     *
     * @param sessionId non-null session identifier
     * @throws IllegalArgumentException if sessionId is null or blank
     */
    public static void set(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be null or blank");
        }
        System.out.println("[ArcClaw Holder] Setting session: " + sessionId + " on thread: " + Thread.currentThread());
        CURRENT_SESSION.set(sessionId);
    }

    /**
     * Returns the session ID bound to the current thread, or {@code null}
     * if no session is active.
     */
    public static String get() {
        return CURRENT_SESSION.get();
    }

    /**
     * Unbind the session from the current thread.
     * <p>
     * <b>Must</b> be called in a {@code finally} block to prevent ThreadLocal leaks
     * on pooled threads. With JDK 25 ScopedValue migration, this becomes
     * unnecessary.
     */
    public static void clear() {
        CURRENT_SESSION.remove();
    }
}
