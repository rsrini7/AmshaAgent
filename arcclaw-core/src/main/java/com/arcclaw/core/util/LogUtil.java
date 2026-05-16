package com.arcclaw.core.util;

/**
 * Delegates to {@link com.arcclaw.common.util.LogUtil}.
 * This class exists only for backward compatibility — new code should import
 * {@code com.arcclaw.common.util.LogUtil} directly.
 *
 * @see com.arcclaw.common.util.LogUtil
 */
public final class LogUtil {

    private LogUtil() {}

    public static String rootMessage(Exception e) {
        return com.arcclaw.common.util.LogUtil.rootMessage(e);
    }

    public static String rootMessage(Throwable e) {
        return com.arcclaw.common.util.LogUtil.rootMessage(e);
    }

    public static String trimmedStack(Exception e, int maxLines) {
        return com.arcclaw.common.util.LogUtil.trimmedStack(e, maxLines);
    }

    public static String trimmedStack(Throwable e, int maxLines) {
        return com.arcclaw.common.util.LogUtil.trimmedStack(e, maxLines);
    }

    public static String trimmedStack(Exception e) {
        return com.arcclaw.common.util.LogUtil.trimmedStack(e);
    }

    public static String trimmedStack(Throwable e) {
        return com.arcclaw.common.util.LogUtil.trimmedStack(e);
    }

    public static String ctx(String sessionId, String role) {
        return com.arcclaw.common.util.LogUtil.ctx(sessionId, role);
    }

    public static String ctx(String sessionId, String role, String tool) {
        return com.arcclaw.common.util.LogUtil.ctx(sessionId, role, tool);
    }
}
