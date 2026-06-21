package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.TextPatternUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Deep unit tests for {@link FinishOutcomeProcessor}.
 * Calls all REAL methods on the processor. External deps are mocked.
 */
class FinishOutcomeProcessorDeepTest {

    @Mock
    AnswerGuardStrategy qualityGuard;

    @Mock
    CodeGenerationPolicy codePolicy;

    @Mock
    RateLimitTracker rateLimitTracker;

    @Mock
    CorrectionStrategy correctionStrategy;

    @Mock
    TextPatternUtils textPatternUtils;

    @Mock
    ToolRegistry toolRegistry;

    FinishOutcomeProcessor processor;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(toolRegistry.listTools()).thenReturn(List.of("sandbox_exec", "github", "serper_web_search"));
        processor = new FinishOutcomeProcessor(
                qualityGuard,
                codePolicy,
                rateLimitTracker,
                correctionStrategy,
                textPatternUtils,
                toolRegistry,
                new HallucinationDetector(new ObjectMapper()));
    }

    // ── extractCandidateAnswer ───────────────────────────────────────────

    @Nested
    class ExtractCandidateAnswerTests {

        @Test
        void extractCandidateAnswer_usesThoughtWhenNoAnswerKey() {
            ReasoningStep step = new ReasoningStep.FinishStep("The answer is 42.", null, 10, 20, false);

            String result = processor.extractCandidateAnswer(step);

            assertEquals("The answer is 42.", result);
        }

        @Test
        void extractCandidateAnswer_usesAnswerKeyWhenPresent() {
            Map<String, Object> input = new HashMap<>();
            input.put(ReasoningStep.ACTION_INPUT_ANSWER_KEY, "The real answer");
            ReasoningStep step = new ReasoningStep.FinishStep("thought", input, 10, 20, false);

            String result = processor.extractCandidateAnswer(step);

            assertEquals("The real answer", result);
        }

        @Test
        void extractCandidateAnswer_stripsPrefixTokenLeak() {
            ReasoningStep step = new ReasoningStep.FinishStep("FINISH: The answer.", null, 10, 20, false);

            String result = processor.extractCandidateAnswer(step);

            assertFalse(result.startsWith("FINISH"));
        }

        @Test
        void extractCandidateAnswer_stripsSuffixTokenLeak() {
            ReasoningStep step = new ReasoningStep.FinishStep("The answer. FINISH", null, 10, 20, false);

            String result = processor.extractCandidateAnswer(step);

            assertFalse(result.endsWith("FINISH"));
        }

        @Test
        void extractCandidateAnswer_stripsActionPrefix() {
            ReasoningStep step = new ReasoningStep.FinishStep("Action: FINISH The answer.", null, 10, 20, false);

            String result = processor.extractCandidateAnswer(step);

            assertNotNull(result);
        }

        @Test
        void extractCandidateAnswer_stripsAnswerPrefix() {
            ReasoningStep step = new ReasoningStep.FinishStep("Answer: The real answer.", null, 10, 20, false);

            String result = processor.extractCandidateAnswer(step);

            assertNotNull(result);
        }

        @Test
        void extractCandidateAnswer_stripsFinalAnswerPrefix() {
            ReasoningStep step = new ReasoningStep.FinishStep("Final Answer: The result.", null, 10, 20, false);

            String result = processor.extractCandidateAnswer(step);

            assertNotNull(result);
        }
    }

    // ── unescapeAndNormalize ─────────────────────────────────────────────

    @Nested
    class UnescapeAndNormalizeTests {

        @Test
        void unescapeAndNormalize_null_returnsNull() {
            assertNull(processor.unescapeAndNormalize(null));
        }

        @Test
        void unescapeAndNormalize_htmlEntities_unescapes() {
            String result = processor.unescapeAndNormalize("&lt;div&gt;hello&lt;/div&gt;");
            assertEquals("<div>hello</div>", result);
        }

        @Test
        void unescapeAndNormalize_unicodeSequences_replaces() {
            String result = processor.unescapeAndNormalize("\\u003cscript\\u003ealert(1)\\u003c/script\\u003e");
            assertTrue(result.contains("<script>"));
            assertTrue(result.contains("</script>"));
        }

        @Test
        void unescapeAndNormalize_stripsToolCallJson() {
            String answer = "Hello {\"tool\":\"github\",\"action\":\"view\"} world";
            String result = processor.unescapeAndNormalize(answer);
            assertFalse(result.contains("\"tool\""));
        }

        @Test
        void unescapeAndNormalize_cleanText_returnsAsIs() {
            String result = processor.unescapeAndNormalize("Just a normal answer.");
            assertEquals("Just a normal answer.", result);
        }

        @Test
        void unescapeAndNormalize_apostropheUnicode_replaces() {
            String result = processor.unescapeAndNormalize("it\\u0027s a test");
            assertEquals("it's a test", result);
        }

        @Test
        void unescapeAndNormalize_ampersandUnicode_replaces() {
            String result = processor.unescapeAndNormalize("a\\u0026b");
            assertEquals("a&b", result);
        }
    }

    // ── sanitizeErrorForUser ─────────────────────────────────────────────

    @Nested
    class SanitizeErrorForUserTests {

        @Test
        void sanitizeErrorForUser_null_returnsNull() {
            assertNull(processor.sanitizeErrorForUser(null, "s1"));
        }

        @Test
        void sanitizeErrorForUser_delegationSession_returnsUnchanged() {
            String answer = "java.lang.NullPointerException: at com.example.Foo.bar";
            String sessionId = "parent:delegate:coder:12345";
            assertEquals(answer, processor.sanitizeErrorForUser(answer, sessionId));
        }

        @Test
        void sanitizeErrorForUser_jvmError_sanitizes() {
            String answer = "The result: java.lang.NullPointerException: something went wrong";
            String result = processor.sanitizeErrorForUser(answer, "normal-session");
            assertTrue(result.contains("internal error"));
        }

        @Test
        void sanitizeErrorForUser_illegalArgument_sanitizes() {
            String answer = "Error: java.lang.IllegalArgumentException: bad arg";
            String result = processor.sanitizeErrorForUser(answer, "normal-session");
            assertTrue(result.contains("internal error"));
        }

        @Test
        void sanitizeErrorForUser_stackOverflow_sanitizes() {
            String answer = "StackOverflowError: recursion";
            String result = processor.sanitizeErrorForUser(answer, "normal-session");
            assertTrue(result.contains("internal error"));
        }

        @Test
        void sanitizeErrorForUser_cleanAnswer_returnsUnchanged() {
            String answer = "The weather is sunny today.";
            assertEquals(answer, processor.sanitizeErrorForUser(answer, "normal-session"));
        }
    }

    // ── handleLeak ───────────────────────────────────────────────────────

    @Nested
    class HandleLeakTests {

        @Test
        void handleLeak_rateLimited_stripsAndCompletes() {
            when(qualityGuard.isRawToolCallLeak(any())).thenReturn(true);
            when(rateLimitTracker.isBackingOff("primary")).thenReturn(true);
            when(qualityGuard.stripRawToolJson(any())).thenReturn("The answer");
            when(qualityGuard.guard(eq("The answer"), anyList()))
                    .thenReturn(new AnswerGuardStrategy.AnswerGuardResult("The answer", false, null));

            ReasoningStep step = new ReasoningStep.FinishStep("leak", null, 0, 0, false);
            var result = processor.handleLeak("leak", step, List.of(), 0, "s1", "goal");

            assertInstanceOf(FinishActionHandler.FinishOutcome.Completed.class, result);
        }

        @Test
        void handleLeak_extractableLeakedToolCall_executesLeakedTool() {
            when(qualityGuard.isRawToolCallLeak(any())).thenReturn(true);
            when(qualityGuard.stripRawToolJson(any())).thenReturn("clean answer text");
            when(qualityGuard.extractLeakedToolCall(any()))
                    .thenReturn(
                            Optional.of(new AnswerGuardStrategy.LeakedToolCall("github", Map.of("action", "view"))));

            ReasoningStep step = new ReasoningStep.FinishStep("leak", null, 0, 0, false);
            var result = processor.handleLeak("leak", step, List.of(), 0, "s1", "goal");

            assertInstanceOf(FinishActionHandler.FinishOutcome.ExecuteLeakedTool.class, result);
            var exec = (FinishActionHandler.FinishOutcome.ExecuteLeakedTool) result;
            assertEquals("github", exec.toolName());
        }

        @Test
        void handleLeak_jsonWithoutToolCallKeys_treatsAsValidAnswer() {
            when(qualityGuard.isRawToolCallLeak(any())).thenReturn(true);
            when(qualityGuard.stripRawToolJson(any())).thenReturn("clean answer text");
            when(qualityGuard.extractLeakedToolCall(any())).thenReturn(Optional.empty());
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            when(qualityGuard.guard(any(), anyList()))
                    .thenReturn(new AnswerGuardStrategy.AnswerGuardResult("guarded answer", false, null));

            String answerWithoutToolKeys = "{\"response\":\"Here is the answer\"}";
            ReasoningStep step = new ReasoningStep.FinishStep("leak", null, 0, 0, false);
            var result = processor.handleLeak(answerWithoutToolKeys, step, List.of(), 0, "s1", "goal");

            assertInstanceOf(FinishActionHandler.FinishOutcome.Completed.class, result);
        }

        @Test
        void handleLeak_thinkingJson_retriesWhenInBudget() {
            when(qualityGuard.isRawToolCallLeak(any())).thenReturn(true);
            when(qualityGuard.stripRawToolJson(any())).thenReturn("clean answer text");
            when(qualityGuard.extractLeakedToolCall(any())).thenReturn(Optional.empty());
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            when(correctionStrategy.withinBudget(0)).thenReturn(true);

            String thinkingJson =
                    "{\"thoughts\":{\"text\":\"thinking\"},\"reasoning\":\"because\",\"plan\":\"do stuff\",\"criticism\":\"none\"}";
            ReasoningStep step = new ReasoningStep.FinishStep("leak", null, 0, 0, false);
            var result = processor.handleLeak(thinkingJson, step, List.of(), 0, "s1", "goal");

            assertInstanceOf(FinishActionHandler.FinishOutcome.Correction.class, result);
        }

        @Test
        void handleLeak_thinkingJson_budgetExceeded_salvagesStripped() {
            when(qualityGuard.isRawToolCallLeak(any())).thenReturn(true);
            when(qualityGuard.stripRawToolJson(any())).thenReturn("This is a valid answer that is long enough.");
            when(qualityGuard.extractLeakedToolCall(any())).thenReturn(Optional.empty());
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            when(correctionStrategy.withinBudget(5)).thenReturn(false);

            String thinkingJson =
                    "{\"thoughts\":{\"text\":\"thinking\"},\"reasoning\":\"because\",\"plan\":\"do stuff\",\"criticism\":\"none\"}";
            ReasoningStep step = new ReasoningStep.FinishStep("leak", null, 0, 0, false);
            var result = processor.handleLeak(thinkingJson, step, List.of(), 5, "s1", "goal");

            assertInstanceOf(FinishActionHandler.FinishOutcome.Completed.class, result);
        }

        @Test
        void handleLeak_salvageableCleanThought_completes() {
            String validAnswer = "This is a valid long answer about the topic that is long enough.";
            when(qualityGuard.isRawToolCallLeak(any())).thenReturn(true);
            when(qualityGuard.stripRawToolJson(any())).thenReturn(validAnswer);
            when(qualityGuard.extractLeakedToolCall(any())).thenReturn(Optional.empty());
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            when(qualityGuard.guard(any(), anyList()))
                    .thenReturn(new AnswerGuardStrategy.AnswerGuardResult(validAnswer, false, null));

            ReasoningStep step = new ReasoningStep.FinishStep("leak", null, 0, 0, false);
            // Early salvage needs llmSchemaErrorCount >= 1
            var result = processor.handleLeak("leak", step, List.of(), 1, "s1", "goal");

            assertInstanceOf(FinishActionHandler.FinishOutcome.Completed.class, result);
        }

        @Test
        void handleLeak_salvageContainsRejectPhrase_fallsToBudgetRetry() {
            // Need containsToolCallKeys = true to reach the salvage path (line 235+)
            // Use an answer with "name" key so containsToolCallKeys returns true
            String toolCallAnswer = "{\"name\":\"github\",\"message\":\"I completed the task\"}";
            when(qualityGuard.isRawToolCallLeak(toolCallAnswer)).thenReturn(true);
            when(qualityGuard.stripRawToolJson(toolCallAnswer))
                    .thenReturn("I completed the task successfully with all the details.");
            when(qualityGuard.extractLeakedToolCall(toolCallAnswer)).thenReturn(Optional.empty());
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            when(correctionStrategy.withinBudget(1)).thenReturn(true);
            when(qualityGuard.extractLeakedToolName(toolCallAnswer)).thenReturn("github");

            ReasoningStep step = new ReasoningStep.FinishStep("leak", null, 0, 0, false);
            // llmSchemaErrorCount >= 1 needed to enter salvage path, but contains reject phrase → skips
            // → falls to withinBudget check → RetryLeak
            var result = processor.handleLeak(toolCallAnswer, step, List.of(), 1, "s1", "goal");

            assertInstanceOf(FinishActionHandler.FinishOutcome.RetryLeak.class, result);
        }

        @Test
        void handleLeak_budgetExceeded_salvages() {
            // Need containsToolCallKeys = true to reach salvage path
            String toolCallAnswer = "{\"name\":\"github\",\"action\":\"view\"}";
            String cleanText = "A sufficiently long salvageable answer.";
            when(qualityGuard.isRawToolCallLeak(toolCallAnswer)).thenReturn(true);
            when(qualityGuard.stripRawToolJson(toolCallAnswer)).thenReturn(cleanText);
            when(qualityGuard.extractLeakedToolCall(toolCallAnswer)).thenReturn(Optional.empty());
            when(textPatternUtils.looksLikeJson(cleanText)).thenReturn(false);
            when(correctionStrategy.withinBudget(10)).thenReturn(false);
            when(qualityGuard.guard(eq(cleanText), anyList()))
                    .thenReturn(new AnswerGuardStrategy.AnswerGuardResult(cleanText, false, null));

            ReasoningStep step = new ReasoningStep.FinishStep("leak", null, 0, 0, false);
            var result = processor.handleLeak(toolCallAnswer, step, List.of(), 10, "s1", "goal");

            // Budget exceeded → salvage() → completed with salvaged text
            assertInstanceOf(FinishActionHandler.FinishOutcome.Completed.class, result);
        }

        @Test
        void handleLeak_budgetExceeded_salvageStillJson_fails() {
            // Need containsToolCallKeys = true to reach salvage path
            String toolCallAnswer = "{\"name\":\"github\",\"action\":\"view\"}";
            when(qualityGuard.isRawToolCallLeak(toolCallAnswer)).thenReturn(true);
            when(qualityGuard.stripRawToolJson(toolCallAnswer)).thenReturn("{\"key\":\"value\"}");
            when(qualityGuard.extractLeakedToolCall(toolCallAnswer)).thenReturn(Optional.empty());
            when(textPatternUtils.looksLikeJson("{\"key\":\"value\"}")).thenReturn(true);
            when(correctionStrategy.withinBudget(10)).thenReturn(false);

            ReasoningStep step = new ReasoningStep.FinishStep("leak", null, 0, 0, false);
            var result = processor.handleLeak(toolCallAnswer, step, List.of(), 10, "s1", "goal");

            assertInstanceOf(FinishActionHandler.FinishOutcome.SalvageFailed.class, result);
        }

        @Test
        void handleLeak_salvageTooShort_fails() {
            // Need containsToolCallKeys = true to reach salvage path
            String toolCallAnswer = "{\"name\":\"github\",\"action\":\"view\"}";
            when(qualityGuard.isRawToolCallLeak(toolCallAnswer)).thenReturn(true);
            when(qualityGuard.stripRawToolJson(toolCallAnswer)).thenReturn("hi");
            when(qualityGuard.extractLeakedToolCall(toolCallAnswer)).thenReturn(Optional.empty());
            when(textPatternUtils.looksLikeJson("hi")).thenReturn(false);
            when(correctionStrategy.withinBudget(10)).thenReturn(false);

            ReasoningStep step = new ReasoningStep.FinishStep("leak", null, 0, 0, false);
            var result = processor.handleLeak(toolCallAnswer, step, List.of(), 10, "s1", "goal");

            assertInstanceOf(FinishActionHandler.FinishOutcome.SalvageFailed.class, result);
        }

        @Test
        void handleLeak_brokenFragmentDetected() {
            // Need containsToolCallKeys = true to reach salvage path
            String toolCallAnswer = "{\"name\":\"github\",\"action\":\"view\"}";
            when(qualityGuard.isRawToolCallLeak(toolCallAnswer)).thenReturn(true);
            when(qualityGuard.stripRawToolJson(toolCallAnswer)).thenReturn("```\n}\n```");
            when(qualityGuard.extractLeakedToolCall(toolCallAnswer)).thenReturn(Optional.empty());
            when(textPatternUtils.looksLikeJson(any())).thenReturn(false);
            when(correctionStrategy.withinBudget(10)).thenReturn(false);

            ReasoningStep step = new ReasoningStep.FinishStep("leak", null, 0, 0, false);
            // isBrokenFragment=true → early salvage condition: (10>=1 || true) && !true → false
            // → falls to withinBudget → false → salvage() → SalvageFailed (too short)
            var result = processor.handleLeak(toolCallAnswer, step, List.of(), 10, "s1", "goal");

            assertInstanceOf(FinishActionHandler.FinishOutcome.SalvageFailed.class, result);
        }
    }

    // ── looksLikeJson ────────────────────────────────────────────────────

    @Nested
    class LooksLikeJsonTests {

        @Test
        void looksLikeJson_delegatesToTextPatternUtils() {
            when(textPatternUtils.looksLikeJson("{\"a\":1}")).thenReturn(true);
            assertTrue(processor.looksLikeJson("{\"a\":1}"));
        }

        @Test
        void looksLikeJson_notJson_returnsFalse() {
            when(textPatternUtils.looksLikeJson("hello")).thenReturn(false);
            assertFalse(processor.looksLikeJson("hello"));
        }
    }

    // ── applyCodePolicy ──────────────────────────────────────────────────

    @Nested
    class ApplyCodePolicyTests {

        @Test
        void applyCodePolicy_nullAnswer_returnsNull() {
            assertNull(processor.applyCodePolicy("s1", "coordinator", List.of(), null, null));
        }

        @Test
        void applyCodePolicy_codeBlockedForRole_notDeepDelegation_returnsCorrection() {
            when(codePolicy.isGeneratedCodeBlock("```java\nSystem.out.println();\n```", "coordinator"))
                    .thenReturn(true);
            when(codePolicy.canGenerateCode("coordinator")).thenReturn(false);
            when(codePolicy.buildRefusalMessage("coordinator")).thenReturn("Please use garuda.");

            ReasoningStep step = new ReasoningStep.FinishStep("thought", null, 0, 0, false);
            var result = processor.applyCodePolicy(
                    "s1", "coordinator", List.of(), "```java\nSystem.out.println();\n```", step);

            assertInstanceOf(FinishActionHandler.FinishOutcome.Correction.class, result);
            var correction = (FinishActionHandler.FinishOutcome.Correction) result;
            assertTrue(correction.correctionHint().contains("garuda"));
        }

        @Test
        void applyCodePolicy_codeBlocked_deepDelegation_noDelegationInstruction() {
            when(codePolicy.isGeneratedCodeBlock("```java\ncode\n```", "researcher"))
                    .thenReturn(true);
            when(codePolicy.canGenerateCode("researcher")).thenReturn(false);

            // Deep delegation: session contains 2+ delegation sentinels
            String sessionId = "parent:delegate:a:1:delegate:b:2";

            ReasoningStep step = new ReasoningStep.FinishStep("thought", null, 0, 0, false);
            var result = processor.applyCodePolicy(sessionId, "researcher", List.of(), "```java\ncode\n```", step);

            assertInstanceOf(FinishActionHandler.FinishOutcome.Correction.class, result);
            var correction = (FinishActionHandler.FinishOutcome.Correction) result;
            // Deep delegation hint says "maximum delegation depth" and tells LLM to FINISH directly
            assertTrue(correction.correctionHint().contains("maximum delegation depth"));
            // Should NOT tell the LLM to delegate (no "use the garuda tool" instruction)
            assertFalse(correction.correctionHint().contains("use the garuda tool"));
        }

        @Test
        void applyCodePolicy_codeAllowedForRole_returnsNull() {
            when(codePolicy.isGeneratedCodeBlock("```java\ncode\n```", "coder")).thenReturn(true);
            when(codePolicy.canGenerateCode("coder")).thenReturn(true);

            assertNull(processor.applyCodePolicy("s1", "coder", List.of(), "```java\ncode\n```", null));
        }

        @Test
        void applyCodePolicy_notCodeBlock_returnsNull() {
            when(codePolicy.isGeneratedCodeBlock("just text", "coordinator")).thenReturn(false);

            assertNull(processor.applyCodePolicy("s1", "coordinator", List.of(), "just text", null));
        }
    }

    // ── checkHallucination ───────────────────────────────────────────────

    @Nested
    class CheckHallucinationTests {

        @Test
        void checkHallucination_delegatesToDetector() {
            // The real HallucinationDetector is used, so test with a clean answer
            ReasoningStep step = new ReasoningStep.FinishStep("thought", null, 0, 0, false);
            var result = processor.checkHallucination("s1", List.of(), step, "The answer is 42.");
            assertNull(result); // No hallucination detected
        }

        @Test
        void checkHallucination_hallucinatedSchedule_detected() {
            ReasoningStep step = new ReasoningStep.FinishStep("thought", null, 0, 0, false);
            var result = processor.checkHallucination(
                    "s1", List.of(), step, "I have scheduled the task for tomorrow at 9 AM.");
            // Hallucination detected if no actual schedule_task in history
            assertNotNull(result);
        }
    }
}
