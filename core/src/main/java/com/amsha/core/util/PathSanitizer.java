package com.amsha.core.util;

import java.util.regex.Pattern;

/**
 * Utility for sanitizing filenames and paths across different operating systems.
 */
public final class PathSanitizer {

    private static final Pattern UNSAFE_PATH_CHARS = Pattern.compile("[:\\\\/*?\"<>|]");

    private PathSanitizer() {
        // utility class
    }

    /**
     * Sanitizes a string for use as a single filesystem component (filename or directory name).
     * Replaces Windows-unsafe characters such as ':', '*', '?', etc. with underscores.
     *
     * @param name The original string (e.g., a sessionId).
     * @return A sanitized string safe for use as a path component on Windows and Unix.
     */
    public static String sanitizeForFilesystem(String name) {
        if (name == null) return null;
        return UNSAFE_PATH_CHARS.matcher(name).replaceAll("_");
    }
}
