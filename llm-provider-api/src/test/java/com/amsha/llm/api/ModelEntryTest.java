package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ModelEntryTest {

    @Test
    void validatesValidEntry() {
        ModelEntry entry = new ModelEntry("gpt-4o", "GPT-4o", "tier-1");
        assertAll(
                () -> assertEquals("gpt-4o", entry.id()),
                () -> assertEquals("GPT-4o", entry.label()),
                () -> assertEquals("tier-1", entry.apiTier()));

        ModelEntry twoArg = new ModelEntry("gpt-4o", "GPT-4o");
        assertAll(
                () -> assertEquals("gpt-4o", twoArg.id()),
                () -> assertEquals("GPT-4o", twoArg.label()),
                () -> assertNull(twoArg.apiTier()));
    }

    @Test
    void rejectsInvalidMandatoryFields() {
        assertThrows(IllegalArgumentException.class, () -> new ModelEntry(null, "Name"));
        assertThrows(IllegalArgumentException.class, () -> new ModelEntry(" ", "Name"));
    }

    @Test
    void defaultsNullOrBlankLabelAndTier() {
        ModelEntry entry = new ModelEntry("id", null, "   ");
        assertAll(
                () -> assertEquals("id", entry.id()),
                () -> assertEquals("id", entry.label()),
                () -> assertNull(entry.apiTier()));
    }
}
