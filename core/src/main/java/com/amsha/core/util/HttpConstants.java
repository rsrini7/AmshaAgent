package com.amsha.core.util;

import java.util.Locale;

/**
 * Centered constants for HTTP headers and web-related strings.
 */
public final class HttpConstants {
    private HttpConstants() {}

    public static final String X_AMSHA_API_KEY = "X-Amsha-API-Key";
    public static final String COOKIE_AMSHA_API_KEY = "AMSHA_API_KEY";

    /**
     * Returns whether a host represents the local machine. This is used only
     * to avoid marking development HTTP cookies as {@code Secure}.
     */
    public static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || normalized.startsWith("127.")
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized)
                || "::ffff:127.0.0.1".equals(normalized)
                || "0:0:0:0:0:ffff:7f00:1".equals(normalized);
    }
}
