package com.amsha.core.agent.loop;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Static utility for detecting if LLM output contains raw JSON that should have been a tool call,
 * or conversely if it contains legitimate natural language.
 */
public class RawJsonGuard {

    private static final Pattern NATURAL_LANGUAGE_GATE =
            Pattern.compile("^([A-Za-z*#\\[]).*\\s.*[.!?]\\s*$", Pattern.DOTALL);

    public static boolean looksLikeRawToolJson(String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.strip();
        // Must look structurally like JSON first (§7.46)
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false;

        String lower = trimmed.toLowerCase(Locale.ROOT);
        // Look for common tool argument keys that should never be in a prose answer
        return lower.contains("\"cronexpression\"")
                || lower.contains("\"cron_expression\"")
                || (lower.contains("\"goal\"") && lower.contains("\"runonce\""))
                || lower.contains("\"task_payload\"")
                || lower.contains("\"task_name\"")
                || (lower.contains("\"agent\"") && lower.contains("\"goal\""));
    }

    public static boolean looksLikeNaturalLanguage(String text) {
        if (text == null || text.length() < 20) return false;
        String trimmed = text.strip();
        char first = trimmed.charAt(0);
        if (first == '{' || first == '<') return false; // allowed '[' for markdown
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("error")
                || lower.contains("exception")
                || lower.contains("invalid")
                || lower.contains("failed")
                || lower.contains("limit")
                || lower.contains("permission")
                || lower.contains("unauthorized")
                || lower.contains("\"code\":")) return false;
        String[] tokens = trimmed.split("\\s+");
        return tokens.length >= 5 && NATURAL_LANGUAGE_GATE.matcher(trimmed).matches();
    }
}
