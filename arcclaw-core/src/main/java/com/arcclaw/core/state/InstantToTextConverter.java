package com.arcclaw.core.state;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for {@link Instant} ↔ SQLite TEXT.
 * <p>
 * Uses fixed-width ISO 8601 format with exactly 3 millisecond digits:
 * {@code yyyy-MM-dd'T'HH:mm:ss.SSSX}. This guarantees lexicographic
 * sort safety in SQLite (all timestamps same width → string comparison
 * equals temporal comparison).
 * <p>
 * Static helper {@link #format(Instant)} is available for native SQL
 * parameter binding in repositories.
 */
@Converter(autoApply = false)
public class InstantToTextConverter implements AttributeConverter<Instant, String> {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")
                    .withZone(ZoneOffset.UTC);

    @Override
    public String convertToDatabaseColumn(Instant attribute) {
        return attribute == null ? null : FORMATTER.format(attribute);
    }

    @Override
    public Instant convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Instant.from(FORMATTER.parse(dbData));
    }

    /**
     * Static helper for native SQL parameter binding.
     *
     * @param instant the instant to format
     * @return fixed-width ISO 8601 text, or null if input is null
     */
    public static String format(Instant instant) {
        return instant == null ? null : FORMATTER.format(instant);
    }
}
