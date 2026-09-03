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

    @Test
    void parsesOneShotScheduleAndRemovesTimeInstruction() {
        ZonedDateTime now = ZonedDateTime.parse("2026-09-02T12:00:00Z");

        var request = CronTimeResolver.parseSchedulingRequest(
                        "Schedule task to check the server logs in 5 minutes", now, ZoneId.of("Asia/Kolkata"))
                .orElseThrow();

        assertEquals("check the server logs", request.goal());
        assertTrue(request.runOnce());
        assertEquals(now.plusMinutes(5), request.targetTime().toInstant().atZone(ZoneOffset.UTC));
        assertNull(request.cronExpression());
    }

    @Test
    void parsesRecurringSchedule() {
        var request = CronTimeResolver.parseSchedulingRequest(
                        "Every 5 minutes run the health check",
                        ZonedDateTime.parse("2026-09-02T12:00:00Z"),
                        ZoneId.of("UTC"))
                .orElseThrow();

        assertEquals("run the health check", request.goal());
        assertFalse(request.runOnce());
        assertEquals("*/5 * * * *", request.cronExpression());
        assertNull(request.targetTime());
    }

    @Test
    void leavesUnrelatedTemporalQuestionOnModelPath() {
        assertTrue(CronTimeResolver.parseSchedulingRequest(
                        "What happens in 5 minutes?", ZonedDateTime.parse("2026-09-02T12:00:00Z"), ZoneId.of("UTC"))
                .isEmpty());
    }
}
