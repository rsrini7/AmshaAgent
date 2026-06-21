package com.amsha.core.agent.loop;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningStep;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Demonstrates CDI @Alternative injection support for orchestration strategies.
 * Requirement T2-4.
 */
@QuarkusTest
public class StrategyInjectionTest {

    @Inject
    Instance<AnswerGuardStrategy> answerGuards;

    @Inject
    Instance<CorrectionStrategy> correctionStrategies;

    @Test
    public void testDefaultImplementationsInjected() {
        // In a normal QuarkusTest without specific alternatives enabled in this class,
        // we should get the primary implementations.
        Assertions.assertNotNull(answerGuards.get());
        Assertions.assertNotNull(correctionStrategies.get());
    }

    /**
     * Mock implementation for demonstration.
     * In a real secondary test, this would be in a separate file or enabled via
     * @TestProfile to show swapping.
     */
    @Alternative
    @Priority(1)
    public static class AlternativeAnswerGuard implements AnswerGuardStrategy {
        @Override
        public AnswerGuardResult guard(String rawAnswer, List<ReasoningStep> history) {
            return new AnswerGuardResult("alternative", true, null);
        }

        @Override
        public boolean isRawToolCallLeak(String answer) {
            return false;
        }

        @Override
        public String stripRawToolJson(String answer) {
            return answer;
        }

        @Override
        public String extractLeakedToolName(String answer) {
            return "alt";
        }

        @Override
        public Optional<LeakedToolCall> extractLeakedToolCall(String answer) {
            return Optional.empty();
        }
    }

    @Alternative
    @Priority(1)
    public static class AlternativeCorrectionStrategy implements CorrectionStrategy {
        @Override
        public String buildCorrectionHint(int currentRetryCount, String failureReason) {
            return "alt hint";
        }

        @Override
        public boolean withinBudget(int currentRetryCount) {
            return true;
        }
    }
}
