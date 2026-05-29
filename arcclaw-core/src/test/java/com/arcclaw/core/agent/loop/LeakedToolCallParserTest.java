package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import com.arcclaw.core.agent.loop.AnswerGuardStrategy.LeakedToolCall;
import com.arcclaw.core.tools.ToolRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LeakedToolCallParserTest {

    LeakedToolCallParser parser;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.listTools()).thenReturn(List.of("garuda", "sandbox_exec", "github"));
        parser = new LeakedToolCallParser(registry);
    }

    @Test
    void extractLeakedToolCall_garudaWithParamsKey() {
        // Small models (Groq llama-4-scout) use "params" instead of "parameters"
        String answer = """
                I will delegate this task.

                ```json
                {
                  "tool": "garuda",
                  "params": {
                    "agent": "kartikeya",
                    "goal": "block_request",
                    "url": "http://169.254.169.254/latest/meta-data/"
                  }
                }
                ```
                """;
        Optional<LeakedToolCall> result = parser.extractLeakedToolCall(answer);
        assertTrue(result.isPresent());
        assertEquals("garuda", result.get().toolName());
        // agent and goal must be top-level, not nested under "params"
        assertEquals("kartikeya", result.get().arguments().get("agent"));
        assertEquals("block_request", result.get().arguments().get("goal"));
    }

    @Test
    void extractLeakedToolCall_garudaWithParametersKey() {
        // Standard format with "parameters"
        String answer = """
                ```json
                {"name": "garuda", "parameters": {"agent": "shiva", "goal": "fix the bug"}}
                ```
                """;
        Optional<LeakedToolCall> result = parser.extractLeakedToolCall(answer);
        assertTrue(result.isPresent());
        assertEquals("garuda", result.get().toolName());
        assertEquals("shiva", result.get().arguments().get("agent"));
    }

    @Test
    void extractLeakedToolCall_garudaWithArgumentsKey() {
        String answer = """
                {"tool_name": "garuda", "arguments": {"agent": "saraswati", "goal": "research"}}
                """;
        Optional<LeakedToolCall> result = parser.extractLeakedToolCall(answer);
        assertTrue(result.isPresent());
        assertEquals("garuda", result.get().toolName());
        assertEquals("saraswati", result.get().arguments().get("agent"));
    }

    @Test
    void extractLeakedToolCall_noToolCall_returnsEmpty() {
        String answer = "Here is the analysis of the code. No issues found.";
        Optional<LeakedToolCall> result = parser.extractLeakedToolCall(answer);
        assertTrue(result.isEmpty());
    }

    @Test
    void extractLeakedToolCall_jsonWithoutToolKeys_returnsEmpty() {
        String answer = "```json\n{\"response\": \"the code is fine\"}\n```";
        Optional<LeakedToolCall> result = parser.extractLeakedToolCall(answer);
        assertTrue(result.isEmpty());
    }
}
