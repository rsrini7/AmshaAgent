package com.arcclaw.core.util;

/**
 * Shared text pattern recognition and validation utilities.
 */
public final class TextPatternUtils {

    private static final int JSON_SALVAGE_MAX_LINES = 20;

    private TextPatternUtils() {}

    /**
     * Returns true if the text appears to be a raw JSON object or array.
     * Includes a line-count guard to avoid false positives on multi-line prose
     * that happens to end with a closing brace.
     */
    public static boolean looksLikeJson(String text) {
        if (text == null) return false;
        String trimmed = text.strip();
        
        // Only flag single-block JSON — multi-line prose ending with } is valid.
        // 20 lines covers all realistic tool-call JSON echoes.
        long lineCount = trimmed.lines().count();
        if (lineCount > JSON_SALVAGE_MAX_LINES) return false;

        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }
}
