package com.arcclaw.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentErrorMessageUtilTest {

    @Test
    void testExtractWaitTime_minutesAndSeconds() {
        assertEquals("7 minutes and 44 seconds",
            AgentErrorMessageUtil.extractWaitTime(
                "Please try again in 7m44.832s. Need more tokens?"));
    }

    @Test
    void testExtractWaitTime_secondsOnly() {
        assertEquals("9 seconds",
            AgentErrorMessageUtil.extractWaitTime(
                "Please try again in 9.365s. Need more tokens?"));
    }

    @Test
    void testExtractWaitTime_minutesOnly() {
        assertEquals("2 minutes",
            AgentErrorMessageUtil.extractWaitTime(
                "Please try again in 2m0s."));
    }

    @Test
    void testExtractWaitTime_singularSecond() {
        assertEquals("1 second",
            AgentErrorMessageUtil.extractWaitTime(
                "Please try again in 1s."));
    }

    @Test
    void testExtractWaitTime_singularMinute() {
        assertEquals("1 minute and 30 seconds",
            AgentErrorMessageUtil.extractWaitTime(
                "Please try again in 1m30s."));
    }

    @Test
    void testExtractWaitTime_noMatch() {
        assertEquals("a few moments",
            AgentErrorMessageUtil.extractWaitTime("some unrelated error"));
    }

    @Test
    void testExtractWaitTime_null() {
        assertEquals("a few moments",
            AgentErrorMessageUtil.extractWaitTime(null));
    }

    @Test
    void testExtractWaitTime_blank() {
        assertEquals("a few moments",
            AgentErrorMessageUtil.extractWaitTime("   "));
    }

    @Test
    void testSanitize_rateLimitExceeded() {
        String raw = "{\"error\":{\"message\":\"Rate limit reached... " +
                     "Please try again in 8m44.832s. Need more tokens?\"," +
                     "\"code\":\"rate_limit_exceeded\"}}";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(raw);
        assertTrue(result.contains("8 minutes and 44 seconds"));
        assertFalse(result.contains("rate_limit_exceeded"));
        assertFalse(result.contains("{"));
    }

    @Test
    void testSanitize_schemaError() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage("SCHEMA_ERROR");
        assertTrue(result.contains("tool execution error"));
        assertFalse(result.contains("SCHEMA_ERROR"));
    }

    @Test
    void testSanitize_null() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(null);
        assertEquals("❌ Task failed. Please try again.", result);
    }

    @Test
    void testSanitize_budgetExceeded() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage("Budget Exceeded");
        assertTrue(result.contains("budget limit"));
    }
}
