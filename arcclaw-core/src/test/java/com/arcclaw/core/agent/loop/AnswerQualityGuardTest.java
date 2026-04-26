package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import com.arcclaw.core.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnswerQualityGuardTest {

    private AnswerQualityGuard guard;
    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools()).thenReturn(List.of(
            "memory_search", "ingest_document", "github", "sandbox_exec", 
            "delegate_to_agent", "schedule_task", "manage_schedule"
        ));
        
        guard = new AnswerQualityGuard();
        guard.toolRegistry = toolRegistry;
        guard.initialize();
    }

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
        
        assertTrue(stripped.contains("Prose before."));
        assertTrue(stripped.contains("Prose after."));
        assertFalse(stripped.contains("type"));
    }

    @Test
    void testNakedFunctionCallLeak() {
        // Suggested case
        assertTrue(guard.isRawToolCallLeak("memory_search(query=\"test\")"),
            "Naked function call pattern must match explicitly");

        String leak = "I will check that for you: memory_search(query=\"what srini loves\")";
        assertTrue(guard.isRawToolCallLeak(leak), "Should detect naked function call as a leak");
        
        String stripped = guard.stripRawToolJson(leak);
        assertEquals("I will check that for you:", stripped);
    }

    @Test
    void testNakedFunctionCallFalsePositiveProtection() {
        // Coder agent common patterns should NOT match
        String coderExplanation = "Use print(end=\"\\n\") to control line endings.";
        assertFalse(guard.isRawToolCallLeak(coderExplanation), 
            "Generic code explanations should NOT be flagged as leaks");
        
        String otherFunction = "Call connect(host=\"localhost\") to open the connection.";
        assertFalse(guard.isRawToolCallLeak(otherFunction),
            "Non-tool function calls should NOT be flagged as leaks");
    }
}
