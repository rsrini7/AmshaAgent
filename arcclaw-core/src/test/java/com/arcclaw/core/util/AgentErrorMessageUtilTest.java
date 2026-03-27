package com.arcclaw.core.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AgentErrorMessageUtilTest {

    @Test
    void testSanitize_standardRateLimitExceeded() {
        // Must include ","type" for the new substring extractor to work
        String raw = "{\"error\":{\"message\":\"Rate limit reached for model `llama-3`... " +
                     "Please try again in 8m44.832s. Need more tokens?\",\"type\":\"tokens\",\"code\":\"rate_limit_exceeded\"}}";
        
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(raw);
        
        assertTrue(result.contains("8 minutes and 45 seconds"), "Should parse and round minutes/seconds");
        assertTrue(result.contains("temporarily throttled") || result.contains("I'm a little busy right now"), "Should use standard throttle message");
        assertFalse(result.contains("{"), "Should not leak JSON");
        assertFalse(result.contains("rate_limit_exceeded"), "Should not leak internal error codes");
    }

    @Test
    void testSanitize_tpdLimitExceededWithPrefixAndHours() {
        // Simulates the exact Groq TPD error with the Java exception prefix
        String raw = "java.lang.RuntimeException: {\"error\":{\"message\":\"Rate limit reached for model `llama-3.3-70b-versatile` " +
                     "in organization `org_123` service tier `on_demand` on tokens per day (TPD): " +
                     "Limit 100000, Used 99854, Requested 5526. Please try again in 1h17m28.32s. " +
                     "Need more tokens?\",\"type\":\"tokens\",\"code\":\"rate_limit_exceeded\"}}";
                     
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(raw);
        
        assertTrue(result.contains("1 hour 17 minutes and 28 seconds"), "Should parse hours, minutes, and seconds");
        assertTrue(result.contains("Limit: 100000"), "Should extract Limit metric");
        assertTrue(result.contains("Used: 99854"), "Should extract Used metric");
        assertTrue(result.contains("Requested: 5526"), "Should extract Requested metric");
        assertFalse(result.contains("java.lang.RuntimeException"), "Should strip Exception prefix");
        assertFalse(result.contains("{"), "Should not leak JSON");
    }

    @Test
    void testSanitize_toolCallValidationFailed() {
        // Simulates the Llama-3 XML hallucination error
        String raw = "TOOL_EXECUTION_FAILED: {\"error\":{\"message\":\"tool call validation failed: attempted to call tool " +
                     "'schedule_task({\\\"goal\\\": \\\"fetch\\\"})' which was not in request.tools\",\"type\":\"invalid_request_error\"}}";
                     
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(raw);
        
        assertEquals("❌ Tool execution error: The agent generated an invalid request.", result);
    }

    @Test
    void testSanitize_schemaErrorText() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage("SCHEMA_ERROR: Missing goal parameter");
        assertEquals("❌ Tool execution error: The agent generated an invalid request.", result);
    }

    @Test
    void testSanitize_null() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(null);
        assertEquals("❌ Task failed. Please try again.", result);
    }

    @Test
    void testSanitize_budgetExceeded() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage("ReasoningEngine error: Budget Exceeded for session");
        assertEquals("❌ Your session budget limit has been reached.", result);
    }

    @Test
    void testSanitize_apiErrorFallback() {
        // Valid JSON error, but not a recognized rate limit or tool error
        String raw = "{\"error\":{\"message\":\"Model overloaded. Please try again later.\",\"type\":\"server_error\"}}";
        
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(raw);
        
        assertEquals("⚠️ API Error: Model overloaded. Please try again later.", result);
    }

    @Test
    void testSanitize_malformedJsonFallback() {
        // Contains { and "error", but fails the specific "message":" and ","type" extraction
        String raw = "{\"error\": \"Unparseable weird format\"}";
        
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(raw);
        
        assertEquals("⚠️ The AI service returned an error. Please try again shortly.", result);
    }
}
