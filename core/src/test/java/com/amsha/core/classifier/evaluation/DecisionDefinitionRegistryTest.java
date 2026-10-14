package com.amsha.core.classifier.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.classifier.api.DecisionDefinition;
import com.amsha.classifier.api.DecisionPrimitive;
import com.amsha.classifier.api.FailMode;
import com.amsha.classifier.api.IntentDecisionPolicy;
import com.amsha.classifier.api.ProviderCategory;
import com.amsha.classifier.api.RetentionPolicy;
import com.amsha.classifier.api.ReviewBand;
import com.amsha.classifier.api.ToolRiskDecisionPolicy;
import com.amsha.core.classifier.DecisionToolRiskShadowClassifier;
import com.amsha.core.routing.IntentDecisionRouter;

import org.junit.jupiter.api.Test;

/**
 * Contract for the decisions the evaluation path is permitted to run.
 *
 * <p>Two entries are load-bearing. The router's own decision is the subject of the Phase 3 evidence;
 * the tool-risk decision is the instrument M1 (false-allow) is measured with. If the tool-risk entry
 * drifts from {@link DecisionToolRiskShadowClassifier} — a different id, band, or option set — an M1
 * measurement would describe a decision the shadow path never asks, so these assertions pin it to the
 * classifier's own vocabulary rather than to a literal copied by hand.
 */
class DecisionDefinitionRegistryTest {

    private static final String INTENT_ID = IntentDecisionRouter.DECISION_ID;
    private static final String TOOL_RISK_ID = DecisionToolRiskShadowClassifier.DECISION_ID;

    private static DecisionDefinitionRegistry productionRegistry() {
        // The values the production bean is constructed with by default.
        return new DecisionDefinitionRegistry(0.50, 0.85, 50_000L);
    }

    @Test
    void seedsExactlyTheTwoDecisionsAGateReads() {
        DecisionDefinitionRegistry registry = productionRegistry();

        assertEquals(
                Set.of(INTENT_ID, TOOL_RISK_ID), registry.decisionIds(), "one decision per gate that reads evidence");
        assertTrue(registry.find(INTENT_ID).isPresent(), "the router's decision must be evaluable");
        assertTrue(registry.find(TOOL_RISK_ID).isPresent(), "the tool-risk decision must be evaluable for M1");
    }

    @Test
    void resolvesByIdExactlyAndNeverDefaults() {
        DecisionDefinitionRegistry registry = productionRegistry();

        assertTrue(registry.find(TOOL_RISK_ID).isPresent());
        assertTrue(registry.find("amsha.routing.unknown.v9").isEmpty(), "an unknown id must not resolve");
        assertTrue(registry.find(null).isEmpty(), "a null id must not resolve");
        assertTrue(registry.find("  ").isEmpty(), "a blank id must not resolve");
    }

    @Test
    void theToolRiskDecisionMirrorsTheShadowClassifiersVocabulary() {
        RegisteredDecision decision = productionRegistry().find(TOOL_RISK_ID).orElseThrow();
        DecisionDefinition definition = decision.definition();

        assertEquals(TOOL_RISK_ID, definition.decisionId());
        assertEquals(DecisionToolRiskShadowClassifier.SCHEMA_VERSION, definition.schemaVersion());
        assertEquals(DecisionPrimitive.CHOICE, definition.primitive());
        assertEquals(
                ToolRiskDecisionPolicy.DEFAULT_REVIEW_BAND,
                definition.reviewBand(),
                "the band must be the one the tool-risk policy resolves against, not an invented one");
        assertEquals(FailMode.FAIL_OPEN, definition.failMode(), "the shadow path observes; it must not tighten");
        assertEquals(RetentionPolicy.EPHEMERAL_EXPORTABLE, definition.retentionPolicy());
        assertFalse(
                definition.retentionPolicy().localOnly(),
                "REMOTE is an allowed provider, so retention cannot be local-only");
        assertTrue(
                definition.allowedProviders().contains(ProviderCategory.REMOTE),
                "the definition mirrors the intent envelope, which admits remote providers");
        assertEquals(Duration.ofMillis(50_000L), definition.timeout());

        assertEquals(1, decision.questions().size(), "the tool-risk decision asks exactly one question");
        QuestionSpec question = decision.questions().getFirst();
        assertEquals(DecisionToolRiskShadowClassifier.TOOL_RISK_KEY.name(), question.handle());
        assertEquals(DecisionPrimitive.CHOICE, question.kind());
        assertEquals(
                DecisionToolRiskShadowClassifier.RISK_OPTIONS,
                question.fixedOptions(),
                "the options are the classifier's, so the registered question is the one the shadow path asks");
        assertFalse(question.callerSupplied(), "a tool-risk question's options are policy, not request data");
    }

    @Test
    void theToolRiskDecisionRefusesCallerSuppliedOptions() {
        RegisteredDecision decision = productionRegistry().find(TOOL_RISK_ID).orElseThrow();

        assertTrue(
                decision.optionsProblem(Map.of("tool_risk", List.of("LOW_RISK", "BLOCK")))
                        .isPresent(),
                "a fixed question must not accept options from a request");
        assertTrue(decision.optionsProblem(Map.of()).isEmpty(), "no supplied options is the expected shape");
    }

    @Test
    void theIntentDecisionStillRequiresItsCallerSuppliedShortlist() {
        RegisteredDecision decision = productionRegistry().find(INTENT_ID).orElseThrow();
        String handle = IntentDecisionRouter.QUESTION_HANDLE;

        assertTrue(decision.questions().getFirst().callerSupplied(), "the intent shortlist is runtime data");
        QuestionSpec question = decision.questions().getFirst();
        assertEquals(IntentDecisionRouter.INTENT_CRITERIA, question.criteria());
        assertEquals(List.of(IntentDecisionPolicy.NO_OPINION_LABEL), question.requiredOptions());
        assertTrue(
                decision.optionsProblem(Map.of(handle, List.of("coder", "researcher")))
                        .isEmpty(),
                "a two-role shortlist is valid");
        assertTrue(
                decision.optionsProblem(Map.of(handle, List.of("coder"))).isEmpty(),
                "one role plus the registry-owned abstention option is valid");
        assertTrue(
                decision.optionsProblem(Map.of(handle, List.of("coder", IntentDecisionPolicy.NO_OPINION_LABEL)))
                        .isPresent(),
                "the caller must not supply a registry-owned option");
        assertTrue(decision.optionsProblem(Map.of()).isPresent(), "the intent decision requires its shortlist");
    }

    @Test
    void aDuplicateRegisteredIdIsRefused() {
        RegisteredDecision decision = productionRegistry().find(TOOL_RISK_ID).orElseThrow();

        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionDefinitionRegistry(List.of(decision, decision)),
                "two decisions may not share an id");
    }

    @Test
    void anUnusableReviewBandIsClampedRatherThanFailingConstruction() {
        // The bean exists even with evaluation disabled, so an inert bean must not fail over a value only
        // the router's own evaluation path consumes.
        DecisionDefinitionRegistry registry = new DecisionDefinitionRegistry(0.9, 0.1, -1L);

        DecisionDefinition definition = registry.find(INTENT_ID).orElseThrow().definition();
        assertEquals(new ReviewBand(0.50, 0.85), definition.reviewBand(), "an inverted band clamps to the defaults");
        assertEquals(Duration.ofMillis(50_000L), definition.timeout(), "a non-positive deadline clamps to the default");
    }
}
