package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.TextPatternUtils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnswerQualityGuardTest {

    private AnswerQualityGuard guard;
    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools())
                .thenReturn(List.of(
                        ToolCapability.RECALL_MEMORY_SEARCH.toolName(),
                        ToolCapability.INGEST_DOCUMENT.toolName(),
                        ToolCapability.GITHUB.toolName(),
                        ToolCapability.SANDBOX_EXEC.toolName(),
                        ToolCapability.DELEGATE_TO_AGENT.toolName(),
                        ToolCapability.SCHEDULE_TASK.toolName(),
                        ToolCapability.MANAGE_SCHEDULE.toolName()));

        guard = new AnswerQualityGuard();
        guard.toolRegistry = toolRegistry;
        guard.textPatternUtils = new TextPatternUtils();
        guard.initialize();
    }

    @Test
    void testRawToolCallLeak() {
        String leak = "Here is the result: {\"type\":\"function\", \"name\":\"sandbox_exec\"}";
        assertTrue(guard.isRawToolCallLeak(leak));
    }

    @Test
    void testXmlJsonToolCallLeak() {
        String leak = """
                <tool_call>
                {"name": "recall_memory_search", "arguments": {"query": "first discussion"}}
                </tool_call>
                """;

        assertTrue(guard.isRawToolCallLeak(leak));
    }

    @Test
    void testDescriptiveBypass() {
        // Build a long string that looks like a capability description
        StringBuilder sb = new StringBuilder();
        sb.append("Amsha Agent capabilities include many features. ");
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
        assertTrue(
                guard.isRawToolCallLeak("recall_memory_search(query=\"test\")"),
                "Naked function call pattern must match explicitly");

        String leak = "I will check that for you: recall_memory_search(query=\"what srini loves\")";
        assertTrue(guard.isRawToolCallLeak(leak), "Should detect naked function call as a leak");

        String stripped = guard.stripRawToolJson(leak);
        assertEquals("I will check that for you:", stripped);
    }

    @Test
    void testNakedFunctionCallFalsePositiveProtection() {
        // Coder agent common patterns should NOT match
        String coderExplanation = "Use print(end=\"\\n\") to control line endings.";
        assertFalse(
                guard.isRawToolCallLeak(coderExplanation), "Generic code explanations should NOT be flagged as leaks");

        String otherFunction = "Call connect(host=\"localhost\") to open the connection.";
        assertFalse(guard.isRawToolCallLeak(otherFunction), "Non-tool function calls should NOT be flagged as leaks");
    }
}
