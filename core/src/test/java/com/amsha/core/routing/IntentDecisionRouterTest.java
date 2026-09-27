package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.DecisionDefinition;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.InMemoryDecisionEngine;
import com.amsha.classifier.api.IntentDecisionAction;
import com.amsha.classifier.api.IntentPolicyResolution;
import com.amsha.classifier.api.QuestionKey;
import com.amsha.classifier.api.ReviewBand;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IntentDecisionRouter}.
 */
@SuppressWarnings("unchecked")
class IntentDecisionRouterTest {

    private static final Set<String> ROLES = Set.of("coordinator", "coder", "researcher");

    private Instance<DecisionEngine> engineInstance;
    private DecisionEngine engine;

    @BeforeEach
    void setUp() {
        engineInstance = mock(Instance.class);
        engine = mock(DecisionEngine.class);
        when(engineInstance.isUnsatisfied()).thenReturn(false);
        when(engineInstance.get()).thenReturn(engine);
    }

    private void returnChoiceForRegisteredQuestion(String decisionId, String role, double confidence) {
        when(engine.evaluate(any())).thenAnswer(invocation -> {
            DecisionRequest request = invocation.getArgument(0);
            QuestionKey<Choice> boundKey = (QuestionKey<Choice>) request.questions()
                    .get(IntentDecisionRouter.QUESTION_HANDLE)
                    .key();
            return DecisionResult.opinionBuilder(decisionId, request.schemaVersion())
                    .put(boundKey, new Choice(role, confidence))
                    .ruleId("rule-1")
                    .build();
        });
    }

    @Test
    @DisplayName("disabled by default: isEnabled returns false and evaluateIntent falls back immediately")
    void disabledByDefault() {
        IntentDecisionRouter router = new IntentDecisionRouter(engineInstance, false, 0.50, 0.85, 500);
        assertFalse(router.isEnabled());

        IntentPolicyResolution res = router.evaluateIntent("write some python code", ROLES);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertEquals("DISABLED", res.reason());
        verify(engine, never()).evaluate(any());
    }

    @Test
    @DisplayName("unsatisfied engine: isEnabled returns false")
    void unsatisfiedEngineReturnsFalse() {
        when(engineInstance.isUnsatisfied()).thenReturn(true);
        IntentDecisionRouter router = new IntentDecisionRouter(engineInstance, true, 0.50, 0.85, 500);
        assertFalse(router.isEnabled());

        IntentPolicyResolution res = router.evaluateIntent("write code", ROLES);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertEquals("DISABLED", res.reason());
    }

    @Test
    @DisplayName("activation gate pending: blocks direct routing even when enabled=true")
    void activationGatePending_blocksDirectRoutingEvenWhenEnabled() {
        IntentDecisionRouter router = new IntentDecisionRouter(engineInstance, true, 0.50, 0.85, 500);
        assertTrue(router.isEnabled());
        assertFalse(router.isActivationGateApproved());
        assertFalse(router.isDirectRoutingPermitted());

        IntentPolicyResolution res = router.evaluateIntent("write some python code", ROLES);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertEquals("ACTIVATION_GATE_NOT_SATISFIED", res.reason());
        verify(engine, never()).evaluate(any());
    }

    @Test
    @DisplayName("activation gate approved: high confidence produces direct route to specialist role")
    void highConfidenceDirectRoute() {
        returnChoiceForRegisteredQuestion(IntentDecisionRouter.DECISION_ID, "coder", 0.92);

        IntentDecisionRouter router =
                new IntentDecisionRouter(engineInstance, true, 0.50, 0.85, 500, true, true, "architecture-committee");
        assertTrue(router.isEnabled());
        assertTrue(router.isActivationGateApproved());
        assertTrue(router.isDirectRoutingPermitted());

        IntentPolicyResolution res = router.evaluateIntent("write a sort algorithm", ROLES);
        assertEquals(IntentDecisionAction.DIRECT_ROUTE, res.action());
        assertFalse(res.fallback());
        assertEquals(Optional.of("coder"), res.selectedRole());
        assertEquals(0.92, res.confidence().orElse(0.0), 0.001);
    }

    @Test
    @DisplayName("real in-memory engine returns bound answer key consumed by router policy")
    void realEngineBoundQuestionKeyProducesDirectRoute() {
        DecisionEngine realEngine = InMemoryDecisionEngine.builder()
                .withAnswer(QuestionKey.choice(IntentDecisionRouter.QUESTION_HANDLE), new Choice("coder", 0.92))
                .build();
        when(engineInstance.get()).thenReturn(realEngine);

        IntentDecisionRouter router =
                new IntentDecisionRouter(engineInstance, true, 0.50, 0.85, 500, true, true, "architecture-committee");

        IntentPolicyResolution res = router.evaluateIntent("write a sort algorithm", ROLES);

        assertEquals(IntentDecisionAction.DIRECT_ROUTE, res.action());
        assertFalse(res.fallback());
        assertEquals(Optional.of("coder"), res.selectedRole());
        assertEquals("HIGH_CONFIDENCE_ROUTE", res.reason());
    }

    @Test
    @DisplayName("activation gate approved: middle band produces secondary review signal")
    void middleBandSecondaryReview() {
        returnChoiceForRegisteredQuestion(IntentDecisionRouter.DECISION_ID, "researcher", 0.68);

        IntentDecisionRouter router =
                new IntentDecisionRouter(engineInstance, true, 0.50, 0.85, 500, true, true, "architecture-committee");
        IntentPolicyResolution res = router.evaluateIntent("how does memory compaction work?", ROLES);
        assertEquals(IntentDecisionAction.SECONDARY_REVIEW, res.action());
        assertFalse(res.fallback());
        assertEquals(Optional.of("researcher"), res.selectedRole());
    }

    @Test
    @DisplayName("activation gate approved: low confidence produces deterministic fallback")
    void lowConfidenceProducesFallback() {
        returnChoiceForRegisteredQuestion(IntentDecisionRouter.DECISION_ID, "coordinator", 0.35);

        IntentDecisionRouter router =
                new IntentDecisionRouter(engineInstance, true, 0.50, 0.85, 500, true, true, "architecture-committee");
        IntentPolicyResolution res = router.evaluateIntent("maybe do something vague", ROLES);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertEquals("LOW_CONFIDENCE", res.reason());
    }

    @Test
    @DisplayName("result identity mismatch: rejects foreign decisionId to fallback before using answer")
    void resultIdentityMismatchRejectsToFallback() {
        QuestionKey<Choice> key = QuestionKey.of(IntentDecisionRouter.QUESTION_HANDLE, Choice.class);
        // Engine returns result for a different decision ID
        DecisionResult foreignOpinion = DecisionResult.opinionBuilder("amsha.security.tool-risk.v1", 1)
                .put(key, new Choice("coder", 0.99))
                .ruleId("rule-1")
                .build();
        when(engine.evaluate(any())).thenReturn(foreignOpinion);

        IntentDecisionRouter router =
                new IntentDecisionRouter(engineInstance, true, 0.50, 0.85, 500, true, true, "architecture-committee");
        IntentPolicyResolution res = router.evaluateIntent("write code", ROLES);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertEquals("DECISION_ID_MISMATCH", res.reason());
    }

    @Test
    @DisplayName("engine throwing exception catches safely and returns fallback")
    void engineExceptionCatchesSafely() {
        when(engine.evaluate(any())).thenThrow(new IllegalStateException("Engine unavailable"));

        IntentDecisionRouter router =
                new IntentDecisionRouter(engineInstance, true, 0.50, 0.85, 500, true, true, "architecture-committee");
        IntentPolicyResolution res = router.evaluateIntent("hello", ROLES);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertEquals("ENGINE_EXCEPTION", res.reason());
    }

    @Test
    @DisplayName("engine NoOpinion preserves failure reason")
    void engineNoOpinionPreservesReason() {
        DecisionResult noOp = new DecisionResult.NoOpinion(
                IntentDecisionRouter.DECISION_ID,
                IntentDecisionRouter.SCHEMA_VERSION,
                ClassifierResult.NoOpinionReason.TIMEOUT,
                null,
                "timeout");
        when(engine.evaluate(any())).thenReturn(noOp);

        IntentDecisionRouter router =
                new IntentDecisionRouter(engineInstance, true, 0.50, 0.85, 500, true, true, "architecture-committee");
        IntentPolicyResolution res = router.evaluateIntent("search docs", ROLES);
        assertEquals(IntentDecisionAction.DETERMINISTIC_FALLBACK, res.action());
        assertTrue(res.fallback());
        assertEquals("TIMEOUT", res.reason());
    }

    @Test
    @DisplayName("decision definition contracts are valid and versioned")
    void decisionDefinitionContract() {
        DecisionDefinition def = IntentDecisionRouter.definition(new ReviewBand(0.50, 0.85), Duration.ofMillis(500));
        assertEquals(IntentDecisionRouter.DECISION_ID, def.decisionId());
        assertEquals(IntentDecisionRouter.SCHEMA_VERSION, def.schemaVersion());
        assertTrue(def.rollbackEnabled());
        assertEquals(Duration.ofMillis(500), def.timeout());
    }
}
