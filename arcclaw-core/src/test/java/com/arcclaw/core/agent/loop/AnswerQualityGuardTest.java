package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AnswerQualityGuardTest {

    private final AnswerQualityGuard guard = new AnswerQualityGuard();

    @Test
    void testRawToolCallLeak() {
        String leak = "Here is the result: {\"type\":\"function\", \"name\":\"sandbox_exec\"}";
        assertTrue(guard.isRawToolCallLeak(leak));
    }

    @Test
    void testDescriptiveBypass() {
        // Build a long string that looks like a capability description
        StringBuilder sb = new StringBuilder();
        sb.append("ArcClaw capabilities include many features. ");
        sb.append("We have several available tools in our arsenal. ");
        sb.append("For example, the sandbox_exec tool has a schema like: ");
        sb.append("{\"type\":\"function\", \"name\":\"sandbox_exec\", \"parameters\": {...}} ");
        
        // Make it > 500 chars
        while (sb.length() < 600) {
            sb.append("This is part of the detailed documentation of system capabilities and available tools. ");
        }
        
        String longAnswer = sb.toString();
        
        // Even though it contains the RAW_TOOL_CALL_PATTERN, it should bypass because it's long and has keywords
        assertFalse(guard.isRawToolCallLeak(longAnswer), "Long descriptive answers should bypass leak detection");
    }

    @Test
    void testShortLeakStillBlocked() {
        String shortLeak = "Check this: {\"type\":\"function\", \"name\":\"sandbox_exec\"}";
        assertTrue(guard.isRawToolCallLeak(shortLeak), "Short strings with JSON should still be blocked");
    }

    @Test
    void testStripRawToolJson() {
        String mix = "Prose before. {\"type\":\"function\", \"name\":\"sandbox_exec\"} Prose after.";
        String stripped = guard.stripRawToolJson(mix);
        
        // Note: stripRawToolJson uses replaceAll on the pattern. 
        // The current implementation in AnswerQualityGuard for stripping is:
        // stripped = stripped.replaceAll("(?s)\\{[^{}]*\"type\"\\s*:\\s*\"function\"[^{}]*}", "");
        
        assertTrue(stripped.contains("Prose before."));
        assertTrue(stripped.contains("Prose after."));
        assertFalse(stripped.contains("type"));
    }
}
