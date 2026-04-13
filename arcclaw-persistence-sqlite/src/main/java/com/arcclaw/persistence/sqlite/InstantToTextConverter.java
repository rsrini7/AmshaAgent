package com.arcclaw.persistence.sqlite;

import java.time.Instant;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.arcclaw.core.util.TemporalUtils;

/**
 * JPA converter for {@link Instant} ↔ SQLite TEXT.
 * <p>
 * Uses fixed-width ISO 8601 format provided by {@link TemporalUtils}.
 * This guarantees lexicographic sort safety in SQLite.
 * <p>
 * Architecture rule (§5.10.1): autoApply is ENABLED in the SQLite adapter
 * module, ensuring all Instant fields in core entities are automatically
 * converted when this module is present.
 */
@Converter(autoApply = true)
public class InstantToTextConverter implements AttributeConverter<Instant, String> {

    @Override
    public String convertToDatabaseColumn(Instant attribute) {
        return TemporalUtils.formatIsoLex(attribute);
    }

    @Override
    public Instant convertToEntityAttribute(String dbData) {
        // We still need to parse. TemporalUtils doesn't have parse yet.
        // Let's keep the formatter local for parsing if needed, or update TemporalUtils.
        return dbData == null ? null : Instant.parse(dbData);
    }

    /**
     * Static helper for native SQL parameter binding.
     * Legacy support — prefer passing Instant to JPQL.
     */
    public static String format(Instant instant) {
        return TemporalUtils.formatIsoLex(instant);
    }
}
