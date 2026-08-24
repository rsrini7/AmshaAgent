package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.loop.AnswerGuardStrategy.LeakedToolCall;
import com.amsha.core.tools.ToolRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LeakedToolCallParserUnitTest {

    @Mock
    ToolRegistry toolRegistry;

    LeakedToolCallParser parser;

    @BeforeEach
    void setUp() {
        when(toolRegistry.listTools()).thenReturn(List.of("execute_shell", "web_fetch", "browser_navigate"));
        parser = new LeakedToolCallParser(toolRegistry);
    }

    @Test
    void testEmptyAnswer() {
        assertTrue(parser.extractLeakedToolCall(null).isEmpty());
        assertTrue(parser.extractLeakedToolCall("").isEmpty());
    }

    @Test
    void testNvidiaToolCallsFormat() {
        String answer = "TOOLCALLS execute_shell {\"command\":\"ls -l\"}";
        Optional<LeakedToolCall> call = parser.extractLeakedToolCall(answer);
        assertTrue(call.isPresent());
        assertEquals("execute_shell", call.get().toolName());
        assertEquals("ls -l", call.get().arguments().get("command"));
    }

    @Test
    void testJsonObjectFormat() {
        String answer = "```json\n{\"name\":\"web_fetch\",\"parameters\":{\"url\":\"http://example.com\"}}\n```";
        Optional<LeakedToolCall> call = parser.extractLeakedToolCall(answer);
        assertTrue(call.isPresent());
        assertEquals("web_fetch", call.get().toolName());
        assertEquals("http://example.com", call.get().arguments().get("url"));
    }

    @Test
    void testToolCallsArrayFormat() {
        String answer =
                "{\"tool_calls\":[{\"name\":\"browser_navigate\",\"arguments\":{\"url\":\"http://example.com\"}}]}";
        Optional<LeakedToolCall> call = parser.extractLeakedToolCall(answer);
        assertTrue(call.isPresent());
        assertEquals("browser_navigate", call.get().toolName());
    }

    @Test
    void testNakedFunctionCallFormat() {
        String answer = "execute_shell(command=\"pwd\")";
        Optional<LeakedToolCall> call = parser.extractLeakedToolCall(answer);
        assertTrue(call.isPresent());
        assertEquals("execute_shell", call.get().toolName());
    }

    @Test
    void testXmlToolCallFormat() {
        String answer = "<function=\"execute_shell\"><parameter name=\"command\">whoami</parameter></function>";
        Optional<LeakedToolCall> call = parser.extractLeakedToolCall(answer);
        assertTrue(call.isPresent());
        assertEquals("execute_shell", call.get().toolName());
        assertEquals("whoami", call.get().arguments().get("command"));
    }

    @Test
    void testDsmlFormat() {
        String answer = "invoke name=\"execute_shell\"><parameter name=\"command\">date</parameter></invoke>";
        Optional<LeakedToolCall> call = parser.extractLeakedToolCall(answer);
        assertTrue(call.isPresent());
        assertEquals("execute_shell", call.get().toolName());
    }

    // ── Ornith-1.5 / MLX-style shapes (regression: these were previously sanitized away
    //    by AgentResultFormatter into the MALFORMED marker instead of being executed) ──

    @Test
    void testUppercaseToolcallTagWithObject() {
        String answer = "<TOOLCALL>{\"name\":\"web_fetch\",\"arguments\":{\"url\":\"http://example.com\"}}</TOOLCALL>";
        Optional<LeakedToolCall> call = parser.extractLeakedToolCall(answer);
        assertTrue(call.isPresent());
        assertEquals("web_fetch", call.get().toolName());
        assertEquals("http://example.com", call.get().arguments().get("url"));
    }

    @Test
    void testUppercaseToolcallTagWithArray() {
        String answer = "<TOOLCALL>[{\"name\":\"execute_shell\",\"arguments\":{\"command\":\"ls -l\"}}]</TOOLCALL>";
        Optional<LeakedToolCall> call = parser.extractLeakedToolCall(answer);
        assertTrue(call.isPresent());
        assertEquals("execute_shell", call.get().toolName());
        assertEquals("ls -l", call.get().arguments().get("command"));
    }

    @Test
    void testLowercaseNoUnderscoreTagVariant() {
        String answer = "<toolcall>{\"name\":\"web_fetch\",\"parameters\":{\"url\":\"http://example.com\"}}</toolcall>";
        Optional<LeakedToolCall> call = parser.extractLeakedToolCall(answer);
        assertTrue(call.isPresent());
        assertEquals("web_fetch", call.get().toolName());
    }

    @Test
    void testBareTopLevelJsonArray() {
        String answer = "[{\"name\":\"browser_navigate\",\"arguments\":{\"url\":\"http://example.com\"}}]";
        Optional<LeakedToolCall> call = parser.extractLeakedToolCall(answer);
        assertTrue(call.isPresent());
        assertEquals("browser_navigate", call.get().toolName());
        assertEquals("http://example.com", call.get().arguments().get("url"));
    }

    @Test
    void testToolcallTagWithUnknownToolIsEmpty() {
        String answer = "<TOOLCALL>{\"name\":\"not_a_real_tool\",\"arguments\":{}}</TOOLCALL>";
        assertTrue(parser.extractLeakedToolCall(answer).isEmpty());
    }

    @Test
    void testLegacyLowercaseUnderscoreTagStillParsed() {
        String answer =
                "<tool_call>{\"name\":\"web_fetch\",\"arguments\":{\"url\":\"http://example.com\"}}</tool_call>";
        Optional<LeakedToolCall> call = parser.extractLeakedToolCall(answer);
        assertTrue(call.isPresent());
        assertEquals("web_fetch", call.get().toolName());
    }
}
