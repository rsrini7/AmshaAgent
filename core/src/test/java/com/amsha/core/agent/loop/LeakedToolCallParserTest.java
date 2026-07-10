package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.loop.AnswerGuardStrategy.LeakedToolCall;
import com.amsha.core.tools.ToolRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LeakedToolCallParserTest {

    LeakedToolCallParser parser;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public List<String> listTools() {
                return List.of("garuda", "sandbox_exec", "github", "host_exec", "recall_memory_search");
            }
        };
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
    void extractLeakedToolCall_dsmlFormat() {
        String answer = """
                I have the template structure.
                <｜｜DSML｜｜tool_calls>
                  <｜｜DSML｜｜invoke name="host_exec">
                    <｜｜DSML｜｜parameter name="code" string="true">mkdir -p output</｜｜DSML｜｜parameter>
                    <｜｜DSML｜｜parameter name="language" string="true">bash</｜｜DSML｜｜parameter>
                  </｜｜DSML｜｜invoke>
                </｜｜DSML｜｜tool_calls>
                """;
        Optional<LeakedToolCall> result = parser.extractLeakedToolCall(answer);
        assertTrue(result.isPresent());
        assertEquals("host_exec", result.get().toolName());
        assertEquals("mkdir -p output", result.get().arguments().get("code"));
        assertEquals("bash", result.get().arguments().get("language"));
    }

    @Test
    void extractLeakedToolCall_xmlFormat() {
        String answer = """
                I have the template structure.
                <tool_call>
                  <function=host_exec>
                    <parameter=code>ls -la /tmp</parameter>
                    <parameter=language>bash</parameter>
                  </function>
                </tool_call>
                """;
        Optional<LeakedToolCall> result = parser.extractLeakedToolCall(answer);
        assertTrue(result.isPresent());
        assertEquals("host_exec", result.get().toolName());
        assertEquals("ls -la /tmp", result.get().arguments().get("code"));
        assertEquals("bash", result.get().arguments().get("language"));
    }

    @Test
    void extractLeakedToolCall_xmlJsonFormat() {
        String answer = """
                <tool_call>
                {"name": "recall_memory_search", "arguments": {"query": "first discussion"}}
                </tool_call>
                """;

        Optional<LeakedToolCall> result = parser.extractLeakedToolCall(answer);

        assertTrue(result.isPresent());
        assertEquals("recall_memory_search", result.get().toolName());
        assertEquals("first discussion", result.get().arguments().get("query"));
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
