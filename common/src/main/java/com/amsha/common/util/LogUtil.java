package com.amsha.common.util;

import org.apache.commons.lang3.exception.ExceptionUtils;

public final class LogUtil {

    private LogUtil() {}

    public static String rootMessage(Exception e) {
        String msg = ExceptionUtils.getRootCauseMessage(e);
        return msg != null ? msg : e.getClass().getSimpleName();
    }

    public static String rootMessage(Throwable e) {
        String msg = ExceptionUtils.getRootCauseMessage(e);
        return msg != null ? msg : e.getClass().getSimpleName();
    }

    public static String trimmedStack(Exception e, int maxLines) {
        return trimmedStack((Throwable) e, maxLines);
    }

    public static String trimmedStack(Throwable e, int maxLines) {
        return ExceptionUtils.getStackTrace(e)
                .lines()
                .limit(maxLines)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }

    public static String trimmedStack(Exception e) {
        return trimmedStack(e, 10);
    }

    public static String trimmedStack(Throwable e) {
        return trimmedStack(e, 10);
    }

    public static String ctx(String sessionId, String role) {
        return "[session=" + sessionId + " role=" + role + "]";
    }

    public static String ctx(String sessionId, String role, String tool) {
        return "[session=" + sessionId + " role=" + role + " tool=" + tool + "]";
    }
}
