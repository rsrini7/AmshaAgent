package com.arcclaw.core.state;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class CronStatusConverter
        implements AttributeConverter<CronStatus, String> {

    @Override
    public String convertToDatabaseColumn(CronStatus attribute) {
        return attribute != null ? attribute.toStorageValue() : null;
    }

    @Override
    public CronStatus convertToEntityAttribute(String dbData) {
        return CronStatus.fromStorageValue(dbData);
    }
}
