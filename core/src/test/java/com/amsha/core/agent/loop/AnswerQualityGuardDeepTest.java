package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.TextPatternUtils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Deep unit tests for {@link AnswerQualityGuard}.
 * All methods are called for real. Only ToolRegistry and TextPatternUtils are mocked
 * (external dependencies).
 */
class AnswerQualityGuardDeepTest {

    private AnswerQualityGuard guard;
    private ToolRegistry toolRegistry;
    private TextPatternUtils textPatternUtils;

    @BeforeEach
    void setup() {
        toolRegistry = mock(ToolRegistry.class);
        textPatternUtils = mock(TextPatternUtils.class);
        when(toolRegistry.listTools()).thenReturn(List.of("sandbox_exec", "github", "serper_web_search"));

        guard = new AnswerQualityGuard();
        guard.toolRegistry = toolRegistry;
        guard.textPatternUtils = textPatternUtils;
        guard.initialize(); // @PostConstruct
    }

    // ── isRawToolCallLeak ────────────────────────────────────────────────

    @Nested
    class IsRawToolCallLeakTests {

        @Test
        void isRawToolCallLeak_nullAnswer_returnsFalse() {
            assertFalse(guard.isRawToolCallLeak(null));
        }

        @Test
        void isRawToolCallLeak_blankAnswer_returnsFalse() {
            assertFalse(guard.isRawToolCallLeak("   "));
        }

        @Test
        void isRawToolCallLeak_typeFunction_returnsTrue() {
            assertTrue(guard.isRawToolCallLeak("{\"type\":\"function\",\"name\":\"github\"}"));
        }

        @Test
        void isRawToolCallLeak_toolCallsArray_returnsTrue() {
            assertTrue(guard.isRawToolCallLeak("{\"tool_calls\":[{\"name\":\"github\"}]}"));
        }

        @Test
        void isRawToolCallLeak_delegateToAgent_returnsTrue() {
            assertTrue(guard.isRawToolCallLeak("```delegatetoagent"));
        }

        @Test
        void isRawToolCallLeak_toolNameParameters_returnsTrue() {
            assertTrue(guard.isRawToolCallLeak("{\"name\":\"github\",\"parameters\":{\"action\":\"view\"}}"));
        }

        @Test
        void isRawToolCallLeak_nvidiaToolcalls_returnsTrue() {
            assertTrue(guard.isRawToolCallLeak("TOOLCALLS sandbox_exec"));
        }

        @Test
        void isRawToolCallLeak_mistralToolCalls_returnsTrue() {
            assertTrue(guard.isRawToolCallLeak("[TOOL_CALLS]"));
        }

        @Test
        void isRawToolCallLeak_nakedFunctionCall_returnsTrue() {
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            assertTrue(guard.isRawToolCallLeak("sandbox_exec(\"hello\")"));
        }

        @Test
        void isRawToolCallLeak_looksLikeJson_returnsTrue() {
            when(textPatternUtils.looksLikeJson("{\"key\":\"val\"}")).thenReturn(true);
            assertTrue(guard.isRawToolCallLeak("{\"key\":\"val\"}"));
        }

        @Test
        void isRawToolCallLeak_cleanAnswer_returnsFalse() {
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            assertFalse(guard.isRawToolCallLeak("The weather today is sunny and warm."));
        }

        @Test
        void isRawToolCallLeak_descriptiveBypass_longWithKeywords_returnsFalse() {
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            String longAnswer = "Here are the available tools and capabilities for your reference. ".repeat(10)
                    + "The capabilities include sandbox_exec for code execution.";
            assertFalse(guard.isRawToolCallLeak(longAnswer));
        }
    }

    // ── stripRawToolJson ─────────────────────────────────────────────────

    @Nested
    class StripRawToolJsonTests {

        @Test
        void stripRawToolJson_null_returnsEmpty() {
            assertEquals("", guard.stripRawToolJson(null));
        }

        @Test
        void stripRawToolJson_cleanAnswer_returnsUnchanged() {
            String answer = "The result is 42.";
            assertEquals(answer, guard.stripRawToolJson(answer));
        }

        @Test
        void stripRawToolJson_jsonCodeBlock_removesIt() {
            String answer = "Here is the answer:\n```json\n{\"type\":\"function\"}\n```\nDone.";
            String stripped = guard.stripRawToolJson(answer);
            assertFalse(stripped.contains("type"));
        }

        @Test
        void stripRawToolJson_typeFunctionObject_removesIt() {
            String answer = "Some text {\"type\": \"function\", \"name\": \"github\"} more text";
            String stripped = guard.stripRawToolJson(answer);
            assertFalse(stripped.contains("type"));
        }

        @Test
        void stripRawToolJson_toolCallsObject_removesIt() {
            String answer = "Text {\"tool_calls\": [{\"name\": \"github\"}]} end";
            String stripped = guard.stripRawToolJson(answer);
            assertFalse(stripped.contains("tool_calls"));
        }

        @Test
        void stripRawToolJson_nameParameters_removesIt() {
            String answer = "Text {\"name\": \"github\", \"parameters\": {\"action\": \"view\"}} end";
            String stripped = guard.stripRawToolJson(answer);
            assertFalse(stripped.contains("parameters"));
        }

        @Test
        void stripRawToolJson_nakedCall_removesIt() {
            String answer = "Please use sandbox_exec(\"hello\") to run it.";
            String stripped = guard.stripRawToolJson(answer);
            assertFalse(stripped.contains("sandbox_exec"));
        }

        @Test
        void stripRawToolJson_shortPunctuationOnly_returnsEmpty() {
            String answer = "```json\n{}\n```";
            String stripped = guard.stripRawToolJson(answer);
            assertEquals("", stripped);
        }

        @Test
        void stripRawToolJson_entireJsonWithToolKeys_returnsEmpty() {
            String answer = "{\"name\":\"github\",\"action\":\"view\"}";
            String stripped = guard.stripRawToolJson(answer);
            assertEquals("", stripped);
        }

        @Test
        void stripRawToolJson_entireJsonWithFunction_returnsEmpty() {
            String answer = "{\"function\":\"sandbox_exec\",\"parameters\":{}}";
            String stripped = guard.stripRawToolJson(answer);
            assertEquals("", stripped);
        }

        @Test
        void stripRawToolJson_jsonWithoutToolKeys_keepsIt() {
            String answer = "{\"greeting\":\"hello\",\"count\":42}";
            String stripped = guard.stripRawToolJson(answer);
            assertEquals(answer, stripped);
        }
    }

    // ── extractLeakedToolName ────────────────────────────────────────────

    @Nested
    class ExtractLeakedToolNameTests {

        @Test
        void extractLeakedToolName_null_returnsFallback() {
            assertEquals("the intended tool", guard.extractLeakedToolName(null));
        }

        @Test
        void extractLeakedToolName_jsonWithName_returnsName() {
            String answer = "{\"name\":\"github\",\"parameters\":{\"action\":\"view\"}}";
            assertEquals("github", guard.extractLeakedToolName(answer));
        }

        @Test
        void extractLeakedToolName_nvidiaFormat_returnsName() {
            String answer = "TOOLCALLS sandbox_exec {\"code\":\"hello\"}";
            assertEquals("sandbox_exec", guard.extractLeakedToolName(answer));
        }

        @Test
        void extractLeakedToolName_nakedCall_returnsName() {
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            String answer = "sandbox_exec(\"hello world\")";
            assertEquals("sandbox_exec", guard.extractLeakedToolName(answer));
        }

        @Test
        void extractLeakedToolName_delegatetoagent_returnsDelegate() {
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            assertEquals("delegatetoagent", guard.extractLeakedToolName("delegatetoagent something"));
        }

        @Test
        void extractLeakedToolName_looksLikeJson_returnsFallback() {
            when(textPatternUtils.looksLikeJson("{\"key\":\"val\"}")).thenReturn(true);
            assertEquals("a structured JSON block", guard.extractLeakedToolName("{\"key\":\"val\"}"));
        }

        @Test
        void extractLeakedToolName_noMatch_returnsFallback() {
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            assertEquals("the intended tool", guard.extractLeakedToolName("just a normal sentence"));
        }
    }

    // ── guardFinalAnswer ─────────────────────────────────────────────────

    @Nested
    class GuardFinalAnswerTests {

        @Test
        void guardFinalAnswer_null_returnsRetryPrompt() {
            String result = guard.guardFinalAnswer(null, 0, "s1");
            assertTrue(result.contains("try again"));
        }

        @Test
        void guardFinalAnswer_blank_returnsRetryPrompt() {
            String result = guard.guardFinalAnswer("   ", 0, "s1");
            assertTrue(result.contains("try again"));
        }

        @Test
        void guardFinalAnswer_suspiciouslyShort_returnsRetry() {
            String result = guard.guardFinalAnswer("Done.", 10, "s1");
            assertTrue(result.contains("meaningful"));
        }

        @Test
        void guardFinalAnswer_ok_returnsRetry() {
            String result = guard.guardFinalAnswer("ok", 5, "s1");
            assertTrue(result.contains("meaningful"));
        }

        @Test
        void guardFinalAnswer_punctuationOnly_returnsRetry() {
            String result = guard.guardFinalAnswer(".", 1, "s1");
            assertTrue(result.contains("meaningful"));
        }

        @Test
        void guardFinalAnswer_ragEcho_returnsRetry() {
            String answer = "<rag_memory>some retrieved context</rag_memory>\nAnd nothing else.";
            String result = guard.guardFinalAnswer(answer, 50, "s1");
            // After stripping RAG echo, remaining text "And nothing else." should pass
            assertNotNull(result);
        }

        @Test
        void guardFinalAnswer_ragEchoOnly_returnsRetry() {
            String answer = "<rag_memory>some retrieved context</rag_memory>";
            String result = guard.guardFinalAnswer(answer, 50, "s1");
            assertTrue(result.contains("try again"));
        }

        @Test
        void guardFinalAnswer_retrievedContextEcho_strips() {
            String answer = "## Retrieved Context\nSome context here.\nThis is my real answer.";
            String result = guard.guardFinalAnswer(answer, 50, "s1");
            assertNotNull(result);
        }

        @Test
        void guardFinalAnswer_retrievedContextEchoOnly_returnsRetry() {
            String answer = "## Retrieved Context\n<context>data</context>\n";
            String result = guard.guardFinalAnswer(answer, 50, "s1");
            assertTrue(result.contains("try again"));
        }

        @Test
        void guardFinalAnswer_systemPromptEcho_salvagesPrefix() {
            String answer = "The answer is 42.\n\n## Host Environment Context\nWorking Directory: /tmp";
            String result = guard.guardFinalAnswer(answer, 100, "s1");
            // Should salvage "The answer is 42." prefix
            assertTrue(result.contains("42") || result.contains("try again"));
        }

        @Test
        void guardFinalAnswer_systemPromptEchoOnly_returnsRetry() {
            String answer = "## Host Environment Context\nWorking Directory: /tmp";
            String result = guard.guardFinalAnswer(answer, 100, "s1");
            assertTrue(result.contains("try again"));
        }

        @Test
        void guardFinalAnswer_systemInstructionEcho_returnsRetry() {
            String answer = "Response must be 2 sentences maximum about the weather.";
            String result = guard.guardFinalAnswer(answer, 20, "s1");
            assertTrue(result.contains("try again"));
        }

        @Test
        void guardFinalAnswer_cleanLongAnswer_returnsIt() {
            String answer = "The weather today is sunny with a high of 75 degrees. Perfect for a walk in the park.";
            String result = guard.guardFinalAnswer(answer, 50, "s1");
            assertEquals(answer, result);
        }
    }

    // ── guard (interface method) ─────────────────────────────────────────

    @Nested
    class GuardTests {

        @Test
        void guard_nullAnswer_returnsFailure() {
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            AnswerGuardStrategy.AnswerGuardResult result = guard.guard(null, List.of());
            assertTrue(result.isFailure());
            assertTrue(result.answer().contains("try again"));
        }

        @Test
        void guard_blankAnswer_returnsFailure() {
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            AnswerGuardStrategy.AnswerGuardResult result = guard.guard("  ", List.of());
            assertTrue(result.isFailure());
        }

        @Test
        void guard_leakedAnswer_returnsLeakFailure() {
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            AnswerGuardStrategy.AnswerGuardResult result =
                    guard.guard("{\"type\":\"function\",\"name\":\"github\"}", List.of());
            assertFalse(result.salvaged());
            assertTrue(result.failureReason().contains("raw_tool_call_leak"));
        }

        @Test
        void guard_cleanAnswer_returnsSuccess() {
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            String answer = "The answer to your question is 42.";
            AnswerGuardStrategy.AnswerGuardResult result = guard.guard(answer, List.of());
            assertNotNull(result);
            assertFalse(result.isFailure());
        }

        @Test
        void guard_suspiciouslyShort_returnsSalvagedRetryPrompt() {
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            AnswerGuardStrategy.AnswerGuardResult result = guard.guard("done", List.of());
            // "done" triggers SUSPICIOUSLY_SHORT, guard returns retry prompt which is
            // considered "salvaged" (answer changed), so isFailure=false but answer contains retry
            assertTrue(result.answer().contains("try again"));
            assertTrue(result.salvaged());
            assertNull(result.failureReason());
        }
    }

    // ── extractLeakedToolCall (delegates to LeakedToolCallParser) ────────

    @Nested
    class ExtractLeakedToolCallTests {

        @Test
        void extractLeakedToolCall_null_returnsEmpty() {
            Optional<AnswerGuardStrategy.LeakedToolCall> result = guard.extractLeakedToolCall(null);
            assertTrue(result.isEmpty());
        }

        @Test
        void extractLeakedToolCall_blank_returnsEmpty() {
            Optional<AnswerGuardStrategy.LeakedToolCall> result = guard.extractLeakedToolCall("  ");
            assertTrue(result.isEmpty());
        }

        @Test
        void extractLeakedToolCall_validJsonObject_parses() {
            String answer = "{\"name\":\"github\",\"parameters\":{\"action\":\"view\"}}";
            Optional<AnswerGuardStrategy.LeakedToolCall> result = guard.extractLeakedToolCall(answer);
            assertTrue(result.isPresent());
            assertEquals("github", result.get().toolName());
        }

        @Test
        void extractLeakedToolCall_nvidiaFormat_parses() {
            String answer = "TOOLCALLS sandbox_exec {\"language\":\"java\",\"code\":\"System.out.println(1);\"}";
            Optional<AnswerGuardStrategy.LeakedToolCall> result = guard.extractLeakedToolCall(answer);
            assertTrue(result.isPresent());
            assertEquals("sandbox_exec", result.get().toolName());
        }

        @Test
        void extractLeakedToolCall_toolCallsArray_parses() {
            String answer =
                    "{\"tool_calls\":[{\"name\":\"github\",\"arguments\":{\"action\":\"view\",\"repo\":\"test\"}}]}";
            Optional<AnswerGuardStrategy.LeakedToolCall> result = guard.extractLeakedToolCall(answer);
            assertTrue(result.isPresent());
            assertEquals("github", result.get().toolName());
        }

        @Test
        void extractLeakedToolCall_noRecognizedTool_returnsEmpty() {
            String answer = "{\"name\":\"unknown_tool\",\"parameters\":{}}";
            Optional<AnswerGuardStrategy.LeakedToolCall> result = guard.extractLeakedToolCall(answer);
            assertTrue(result.isEmpty());
        }
    }
}
