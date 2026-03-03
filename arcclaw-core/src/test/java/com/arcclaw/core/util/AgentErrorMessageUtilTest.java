package com.arcclaw.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentErrorMessageUtilTest {

    @Test
    void testSanitize_rateLimitExceeded() {
        String raw = "{\"error\":{\"message\":\"Rate limit reached... " +
                     "Please try again in 8m44.832s. Need more tokens?\"," +
                     "\"code\":\"rate_limit_exceeded\"}}";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(raw);
        assertTrue(result.contains("8 minutes and 45 seconds"));
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
