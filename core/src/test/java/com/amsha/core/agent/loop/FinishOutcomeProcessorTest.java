package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.TextPatternUtils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FinishOutcomeProcessor}: answer extraction, leak handling,
 * code-policy gating and error sanitization.
 */
public class FinishOutcomeProcessorTest {

    private AnswerGuardStrategy qualityGuard;
    private CodeGenerationPolicy codePolicy;
    private RateLimitTracker rateLimitTracker;
    private CorrectionStrategy correctionStrategy;
    private ToolRegistry toolRegistry;
    private HallucinationDetector hallucinationDetector;
    private IntentClassificationService intentClassifier;
    private FinishOutcomeProcessor processor;

    @BeforeEach
    public void setUp() {
        qualityGuard = mock(AnswerGuardStrategy.class);
        codePolicy = mock(CodeGenerationPolicy.class);
        rateLimitTracker = mock(RateLimitTracker.class);
        correctionStrategy = mock(CorrectionStrategy.class);
        toolRegistry = mock(ToolRegistry.class);
        hallucinationDetector = mock(HallucinationDetector.class);
        intentClassifier = mock(IntentClassificationService.class);
        processor = new FinishOutcomeProcessor(
                qualityGuard,
                codePolicy,
                rateLimitTracker,
                correctionStrategy,
                new TextPatternUtils(),
                toolRegistry,
                hallucinationDetector,
                intentClassifier);
    }

    private ReasoningStep finishStep(String thought, String answer) {
        return new ReasoningStep.FinishStep(
                thought, Collections.singletonMap(ReasoningStep.ACTION_INPUT_ANSWER_KEY, answer), 0, 0, false);
    }

    @Test
    public void extractCandidateAnswerPrefersAnswerKeyOverThought() {
        ReasoningStep step = finishStep("thought text", "real answer");
        assertEquals("real answer", processor.extractCandidateAnswer(step));
    }

    @Test
    public void extractCandidateAnswerStripsPrefixAndSuffixLeaks() {
        assertEquals("Hello world", processor.extractCandidateAnswer(finishStep("t", "FINISH: Hello world")));
        assertEquals("Hello world", processor.extractCandidateAnswer(finishStep("t", "Answer: Hello world FINISH")));
        // No answer key present -> falls back to the raw thought
        ReasoningStep noAnswer = new ReasoningStep.FinishStep("Just prose", Map.of(), 0, 0, false);
        assertEquals("Just prose", processor.extractCandidateAnswer(noAnswer));
    }

    @Test
    public void unescapeAndNormalizesEntitiesAndUnicodeEscapes() {
        assertEquals("<a> & '", processor.unescapeAndNormalize("&lt;a&gt; &amp; \\u0027"));
        assertNull(processor.unescapeAndNormalize(null));
    }

    @Test
    public void sanitizeErrorForUserBlocksJvmExceptionLeaks() {
        String leaked = "The task failed: java.lang.NullPointerException: cannot invoke";
        assertEquals(
                "I encountered an internal error while formatting the response. Please try again.",
                processor.sanitizeErrorForUser(leaked, "sess-1"));
        assertEquals("plain fine answer", processor.sanitizeErrorForUser("plain fine answer", "sess-1"));
        assertNull(processor.sanitizeErrorForUser(null, "sess-1"));
    }

    @Test
    public void codePolicyInjectsDelegateForBlockedCodeGen() {
        String answer = "Here is code:\n```java\nint x = 1;\n```";
        when(codePolicy.isGeneratedCodeBlock(anyString(), anyString())).thenReturn(true);
        when(codePolicy.canGenerateCode(eq("coordinator"))).thenReturn(false);
        when(intentClassifier.isSimpleConversational(anyString())).thenReturn(false);

        var outcome = processor.applyCodePolicy(
                "sess-1", "coordinator", "write a program", List.of(), answer, finishStep("t", answer));

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.InjectToolCall inject
                && inject.toolName().equals("garuda"));
    }

    @Test
    public void codePolicyAllowsRolesThatMayGenerateCode() {
        when(codePolicy.isGeneratedCodeBlock(anyString(), anyString())).thenReturn(false);
        assertNull(processor.applyCodePolicy(
                "sess-1", "coder", "goal", List.of(), "```java\nx\n```", finishStep("t", "a")));
    }

    @Test
    public void handleLeakWithBackingOffRateLimitSalvagesStrippedAnswer() {
        when(qualityGuard.isRawToolCallLeak(anyString())).thenReturn(true);
        when(rateLimitTracker.isBackingOff("primary")).thenReturn(true);
        when(qualityGuard.stripRawToolJson(anyString())).thenReturn("clean prose answer");
        when(qualityGuard.guard(eq("clean prose answer"), anyList()))
                .thenReturn(new AnswerGuardStrategy.AnswerGuardResult("clean prose answer", false, null));

        var outcome = processor.handleLeak("{bad json}", finishStep("t", "{bad json}"), List.of(), 0, "s1", "goal");

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Completed completed
                && "clean prose answer".equals(completed.answer()));
    }

    @Test
    public void handleLeakExecutesExtractedLeakedToolCall() {
        when(qualityGuard.isRawToolCallLeak(anyString())).thenReturn(true);
        when(rateLimitTracker.isBackingOff(anyString())).thenReturn(false);
        when(qualityGuard.stripRawToolJson(anyString())).thenReturn("");
        when(qualityGuard.extractLeakedToolCall(anyString()))
                .thenReturn(Optional.of(new AnswerGuardStrategy.LeakedToolCall("web_search", Map.of("query", "x"))));

        var outcome =
                processor.handleLeak("{\"name\": \"web_search\"}", finishStep("t", "leak"), List.of(), 0, "s1", "goal");

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.ExecuteLeakedTool exec
                && "web_search".equals(exec.toolName()));
    }

    @Test
    public void rawJsonInChitchatWithinBudgetRetriesWithProseHint() {
        String json = "{\"status\": \"ok\"}";
        when(qualityGuard.isRawToolCallLeak(json)).thenReturn(false);
        when(rateLimitTracker.isBackingOff(anyString())).thenReturn(false);
        when(qualityGuard.stripRawToolJson(anyString())).thenReturn("");
        when(qualityGuard.extractLeakedToolCall(anyString())).thenReturn(Optional.empty());
        when(intentClassifier.isSimpleConversational(anyString())).thenReturn(true);
        when(intentClassifier.isGreeting(anyString())).thenReturn(true);
        when(correctionStrategy.withinBudget(0)).thenReturn(true);

        var outcome = processor.handleLeak(json, finishStep("t", json), List.of(), 0, "s1", "hello there");

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Correction);
    }

    @Test
    public void exhaustedBudgetWithUnsalvageableAnswerFailsTheTurn() {
        String fence = "```json```";
        when(qualityGuard.isRawToolCallLeak(anyString())).thenReturn(true);
        when(rateLimitTracker.isBackingOff(anyString())).thenReturn(false);
        when(qualityGuard.stripRawToolJson(anyString())).thenReturn(fence);
        when(qualityGuard.extractLeakedToolCall(anyString())).thenReturn(Optional.empty());

        var outcome = processor.handleLeak("TOOLCALLS {}", finishStep("t", "leak"), List.of(), 5, "s1", "goal");

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.SalvageFailed failed
                && failed.result() instanceof AgentResult.Failed);
    }

    @Test
    public void exhaustedBudgetSalvagesCleanTextWhenAvailable() {
        when(qualityGuard.isRawToolCallLeak(anyString())).thenReturn(true);
        when(rateLimitTracker.isBackingOff(anyString())).thenReturn(false);
        when(qualityGuard.stripRawToolJson(anyString()))
                .thenReturn("This is a perfectly usable plain thought for the user.");
        when(qualityGuard.extractLeakedToolCall(anyString())).thenReturn(Optional.empty());
        when(intentClassifier.isSimpleConversational(anyString())).thenReturn(false);
        when(toolRegistry.listTools()).thenReturn(List.of());
        // Answer contains TOOLCALLS (tool-call keys), so the JSON-guard block is skipped and
        // the early-salvage branch runs on the clean thought.
        String leaked = "TOOLCALLS [{\"name\": \"x\"}]";
        var outcome = processor.handleLeak(leaked, finishStep("leak", leaked), List.of(), 9, "s1", "goal");

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Completed completed
                && completed.answer().contains("perfectly usable"));
    }

    @Test
    public void withinBudgetGenericLeakProducesRetryLeakWithToolName() {
        String leaked = "{\"action\": {\"tool\": \"calculator\"}} some trailing words";
        when(qualityGuard.isRawToolCallLeak(anyString())).thenReturn(true);
        when(rateLimitTracker.isBackingOff(anyString())).thenReturn(false);
        when(qualityGuard.stripRawToolJson(anyString())).thenReturn("");
        when(qualityGuard.extractLeakedToolCall(anyString())).thenReturn(Optional.empty());
        when(qualityGuard.extractLeakedToolName(anyString())).thenReturn("calculator");
        when(correctionStrategy.withinBudget(0)).thenReturn(true);
        when(intentClassifier.isSimpleConversational(anyString())).thenReturn(false);

        var outcome = processor.handleLeak(leaked, finishStep("thought body", leaked), List.of(), 0, "s1", "goal");

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.RetryLeak retry
                && "calculator".equals(retry.leakedToolName()));
    }

    @Test
    public void earlySalvageOnSecondLeakInjectsSearchForResearchRefusal() {
        // Non-leak prose refusal on a research goal after one schema error: the early-salvage
        // branch should guard the clean thought and inject a search tool.
        String refusal = "I cannot help because that information is not available to me right now.";
        when(qualityGuard.isRawToolCallLeak(refusal)).thenReturn(false);
        when(rateLimitTracker.isBackingOff(anyString())).thenReturn(false);
        when(qualityGuard.stripRawToolJson(refusal)).thenReturn(refusal);
        when(qualityGuard.extractLeakedToolCall(refusal)).thenReturn(Optional.empty());
        when(intentClassifier.isSimpleConversational(anyString())).thenReturn(false);
        when(toolRegistry.listTools()).thenReturn(List.of("web_search_serper", "calculator"));
        when(qualityGuard.guard(eq(refusal), anyList()))
                .thenReturn(new AnswerGuardStrategy.AnswerGuardResult(refusal, false, null));

        var outcome =
                processor.handleLeak(refusal, finishStep(refusal, null), List.of(), 1, "s1", "research fusion energy");

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.InjectToolCall inject
                && inject.toolName().startsWith("web_search")
                && inject.arguments().containsKey("query"));
    }

    @Test
    public void hallucinationAndScheduleDelegatesAreInvoked() {
        var step = finishStep("t", "a");
        when(hallucinationDetector.checkHallucination(any(), anyList(), any(), anyString()))
                .thenReturn(new FinishActionHandler.FinishOutcome.Correction(step, "hint"));
        when(hallucinationDetector.historyContainsSuccessfulSchedule(anyList())).thenReturn(true);

        assertEquals(
                "hint",
                ((FinishActionHandler.FinishOutcome.Correction)
                                processor.checkHallucination("s", List.of(), step, "ans"))
                        .correctionHint());
        assertTrue(processor.historyContainsSuccessfulSchedule(List.of(step)));
    }

    @Test
    public void looksLikeJsonDelegatesToTextPatternUtils() {
        assertTrue(processor.looksLikeJson("{\"a\": 1}"));
        assertFalse(processor.looksLikeJson("plain text"));
    }
}
