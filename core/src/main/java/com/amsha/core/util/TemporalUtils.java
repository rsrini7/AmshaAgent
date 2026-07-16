package com.amsha.core.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Utility for temporal formatting and parsing, backend-agnostic.
 * <p>
 * Standardizes ISO 8601 lexicographical formatting for backends that lack
 * native temporal types (like SQLite).
 */
public class TemporalUtils {

    private static final DateTimeFormatter ISO_LEX_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);

    /**
     * Fallback for SQLite {@code datetime('now')} values, which render as
     * {@code yyyy-MM-dd HH:mm:ss} (space separator, no offset).
     */
    private static final DateTimeFormatter SQLITE_DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    /**
     * Formats an {@link Instant} as a fixed-width ISO 8601 string.
     * Guaranteed lexicographical sorting (string compare == temporal compare).
     *
     * @param instant the instant to format
     * @return ISO 8601 string, or null if input is null
     */
    public static String formatIsoLex(Instant instant) {
        return instant == null ? null : ISO_LEX_FORMATTER.format(instant);
    }

    /**
     * Parses an ISO 8601 lexicographical string back into an {@link Instant}.
     *
     * @param text the text to parse
     * @return the parsed Instant, or null if input is null
     */
    public static Instant parseIsoLex(String text) {
        if (text == null) return null;
        try {
            return Instant.from(ISO_LEX_FORMATTER.parse(text));
        } catch (Exception e) {
            // Fallback for SQLite datetime('now') values: "yyyy-MM-dd HH:mm:ss"
            try {
                return Instant.from(SQLITE_DATETIME_FORMATTER.parse(text));
            } catch (Exception ignored) {
                // Fallback for legacy data stored as epoch milliseconds
                try {
                    return Instant.ofEpochMilli(Long.parseLong(text));
                } catch (NumberFormatException nfe) {
                    throw new RuntimeException("Failed to parse instant: " + text, e);
                }
            }
        }
    }
}
