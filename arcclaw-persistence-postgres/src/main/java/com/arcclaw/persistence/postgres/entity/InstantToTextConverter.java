package com.arcclaw.persistence.postgres.entity;

import java.time.Instant;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.arcclaw.core.util.TemporalUtils;

/**
 * JPA converter for {@link Instant} Γåö SQLite TEXT.
 * <p>
 * Uses fixed-width ISO 8601 format provided by {@link TemporalUtils}.
 * This guarantees lexicographic sort safety in SQLite.
 * <p>
 * Architecture rule (┬º5.10.1): autoApply is ENABLED, ensuring all Instant 
 * fields in core entities are automatically converted.
 */
@Converter(autoApply = true)
public class InstantToTextConverter implements AttributeConverter<Instant, String> {

    @Override
    public String convertToDatabaseColumn(Instant attribute) {
        return TemporalUtils.formatIsoLex(attribute);
    }

    @Override
    public Instant convertToEntityAttribute(String dbData) {
        return TemporalUtils.parseIsoLex(dbData);
    }

    /**
     * Static helper for native SQL parameter binding.
     * Legacy support - prefer passing Instant to JPQL.
     */
    public static String format(Instant instant) {
        return TemporalUtils.formatIsoLex(instant);
    }
}

