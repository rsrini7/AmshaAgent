package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.TextPatternUtils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FinishOutcomeProcessorUnitTest {

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

    @Mock
    HallucinationDetector hallucinationDetector;

    @InjectMocks
    FinishOutcomeProcessor processor;

    @Test
    void testExtractCandidateAnswer() {
        ReasoningStep step = ReasoningStep.pending(
                "Thinking",
                ReasoningStep.FINISH,
                Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, "Here is the final answer."));
        String extracted = processor.extractCandidateAnswer(step);
        assertEquals("Here is the final answer.", extracted);
    }

    @Test
    void testUnescapeAndNormalize() {
        String input = "Hello &amp; welcome &lt;world&gt;";
        String normalized = processor.unescapeAndNormalize(input);
        assertEquals("Hello & welcome <world>", normalized);
    }

    @Test
    void testSanitizeErrorForUserInternalJvm() {
        String answer = "Exception occurred: java.lang.NullPointerException: null value at line 10";
        String sanitized = processor.sanitizeErrorForUser(answer, "sess-123");
        assertEquals("I encountered an internal error while formatting the response. Please try again.", sanitized);
    }
}
