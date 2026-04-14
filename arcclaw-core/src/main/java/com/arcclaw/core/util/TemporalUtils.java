package com.arcclaw.core.util;

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
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")
                    .withZone(ZoneOffset.UTC);

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
        return text == null ? null : Instant.from(ISO_LEX_FORMATTER.parse(text));
    }
}
