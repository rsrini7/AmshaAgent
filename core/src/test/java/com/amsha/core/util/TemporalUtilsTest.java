package com.amsha.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class TemporalUtilsTest {

    @Test
    void parsesIsoLexWithMillisAndOffset() {
        Instant expected = Instant.parse("2026-07-15T15:06:37.413Z");
        assertEquals(expected, TemporalUtils.parseIsoLex("2026-07-15T15:06:37.413Z"));
    }

    @Test
    void parsesIsoLexWithoutMillis() {
        // Mapper pattern requires .SSS; use a millisecond-aligned instant so
        // format (truncates to millis) round-trips exactly.
        Instant now = Instant.ofEpochMilli(Instant.now().toEpochMilli());
        String formatted = TemporalUtils.formatIsoLex(now);
        assertEquals(now, TemporalUtils.parseIsoLex(formatted));
    }

    @Test
    void parsesSqliteDatetimeNowSpaceFormat() {
        // SQLite datetime('now') default renders as "yyyy-MM-dd HH:mm:ss".
        Instant expected = Instant.parse("2026-07-15T15:06:37Z");
        assertEquals(expected, TemporalUtils.parseIsoLex("2026-07-15 15:06:37"));
    }

    @Test
    void parsesEpochMillis() {
        Instant expected = Instant.ofEpochMilli(1783789868897L);
        assertEquals(expected, TemporalUtils.parseIsoLex("1783789868897"));
    }

    @Test
    void returnsNullForNullInput() {
        assertNull(TemporalUtils.parseIsoLex(null));
    }

    @Test
    void formatIsoLexRoundTrips() {
        Instant instant = Instant.parse("2026-07-15T15:06:37.000Z");
        assertEquals(instant, TemporalUtils.parseIsoLex(TemporalUtils.formatIsoLex(instant)));
    }
}
