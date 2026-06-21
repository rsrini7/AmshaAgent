package com.amsha.core.util;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

public class CronTimeResolverTest {

    @Test
    public void testInAMin() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime resolved =
                CronTimeResolver.resolveTargetTime("schedule in a min", now, ZoneId.of("Asia/Kolkata"));
        assertNotNull(resolved);
        // Should be roughly 1 min from now
        assertTrue(resolved.isAfter(now));
        assertTrue(resolved.isBefore(now.plusMinutes(2).plusSeconds(1)));
    }

    @Test
    public void testIn5Minutes() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime resolved =
                CronTimeResolver.resolveTargetTime("remind me in 5 minutes", now, ZoneId.of("Asia/Kolkata"));
        assertNotNull(resolved);
        // Should be exactly 5 min from now
        assertEquals(now.plusMinutes(5), resolved);
    }

    @Test
    public void testAtTime() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime resolved = CronTimeResolver.resolveTargetTime("at 10:30 pm", now, ZoneId.of("Asia/Kolkata"));
        assertNotNull(resolved);
    }

    @Test
    public void testNegation() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime resolved =
                CronTimeResolver.resolveTargetTime("don't schedule in a min", now, ZoneId.of("Asia/Kolkata"));
        assertNull(resolved);
    }
}
