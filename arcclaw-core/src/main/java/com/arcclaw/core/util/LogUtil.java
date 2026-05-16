package com.arcclaw.core.util;

import org.apache.commons.lang3.exception.ExceptionUtils;

/**
 * Centralized logging helpers for consistent, compact log output.
 * <p>
 * All exception logging in ArcClaw should use these utilities instead of
 * raw {@code e.getMessage()} or {@code e.printStackTrace()}.
 *
 * <h3>Why</h3>
 * <ul>
 *   <li>{@link #rootMessage(Exception)} — always returns the deepest cause, never null</li>
 *   <li>{@link #trimmedStack(Exception, int)} — full stack trimmed to N lines for WARN/ERROR logs</li>
 *   <li>{@link #ctx} — consistent "[session=X role=Y]" context prefix</li>
 * </ul>
 */
public final class LogUtil {

    private LogUtil() {}

    // ── Exception formatting ──

    /**
     * Returns the root cause message, or the exception message if no cause chain exists.
     * Never returns null — falls back to exception class name.
     */
    public static String rootMessage(Exception e) {
        String msg = ExceptionUtils.getRootCauseMessage(e);
        return msg != null ? msg : e.getClass().getSimpleName();
    }

    /**
     * Returns the root cause message for any Throwable.
     */
    public static String rootMessage(Throwable e) {
        String msg = ExceptionUtils.getRootCauseMessage(e);
        return msg != null ? msg : e.getClass().getSimpleName();
    }

    /**
     * Returns the full stack trace trimmed to {@code maxLines} lines.
     * Useful for WARN/ERROR logs where the full trace is needed for diagnosis
     * but dumping 50+ lines is noise.
     */
    public static String trimmedStack(Exception e, int maxLines) {
        return trimmedStack((Throwable) e, maxLines);
    }

    /**
     * Returns the full stack trace trimmed to {@code maxLines} lines.
     */
    public static String trimmedStack(Throwable e, int maxLines) {
        return ExceptionUtils.getStackTrace(e)
                .lines()
                .limit(maxLines)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }

    /**
     * Returns the full stack trace trimmed to 10 lines (default).
     */
    public static String trimmedStack(Exception e) {
        return trimmedStack(e, 10);
    }

    /**
     * Returns the full stack trace trimmed to 10 lines (default) for any Throwable.
     */
    public static String trimmedStack(Throwable e) {
        return trimmedStack(e, 10);
    }

    // ── Context formatting ──

    /**
     * Formats a log context prefix: {@code [session=X role=Y]}.
     */
    public static String ctx(String sessionId, String role) {
        return "[session=" + sessionId + " role=" + role + "]";
    }

    /**
     * Formats a log context prefix with tool name: {@code [session=X role=Y tool=Z]}.
     */
    public static String ctx(String sessionId, String role, String tool) {
        return "[session=" + sessionId + " role=" + role + " tool=" + tool + "]";
    }
}
