package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ToolRiskDecisionPolicy} and {@link CandidateReductionPolicy}.
 * Verifies review bands, strictly monotonic risk escalation, deterministic fallback,
 * and payload-free bounded outcome metadata.
 */
class ToolRiskDecisionPolicyTest {

    private static final ReviewBand BAND = new ReviewBand(0.4, 0.8);

    @Test
    void candidateReductionPolicyFiltersAndCapsCandidates() {
        CandidateReductionPolicy policyWithShortlist =
                new CandidateReductionPolicy("SHORTLIST", "Only safe tools", 3, List.of("bash", "git", "grep"));
        List<String> input = List.of("bash", "curl", "git", "rm", "grep");
        List<String> filtered = policyWithShortlist.filter(input);

        assertEquals(List.of("bash", "git", "grep"), filtered);

        CandidateReductionPolicy policyWithCap = new CandidateReductionPolicy("DYNAMIC_CAP", "Cap at 2", 2, List.of());
        assertEquals(List.of("bash", "curl"), policyWithCap.filter(input));

        CandidateReductionPolicy openPolicy = CandidateReductionPolicy.none();
        assertEquals(List.of("a", "b"), openPolicy.filter(List.of("a", "b")));
        assertTrue(openPolicy.filter(null).isEmpty());
        assertTrue(openPolicy.filter(List.of()).isEmpty());
    }

    @Test
    void monotonicEscalationCannotDowngradeRisk() {
        // ALLOW (null) escalated to REQUIRE_APPROVAL
        DecisionResult opReview = createChoiceResult("dec.1", "REQUIRE_APPROVAL", OptionalDouble.empty());
        ToolRiskDecisionPolicy.PolicyResolution res1 =
                ToolRiskDecisionPolicy.resolve(null, opReview, BAND, FailMode.FAIL_OPEN);
        assertEquals(ToolRiskAction.REQUIRE_APPROVAL, res1.effectiveAction());
        assertTrue(res1.escalated());
        assertFalse(res1.fallback());

        // ALLOW (null) escalated to BLOCK
        DecisionResult opBlock = createChoiceResult("dec.2", "BLOCK", OptionalDouble.empty());
        ToolRiskDecisionPolicy.PolicyResolution res2 =
                ToolRiskDecisionPolicy.resolve(null, opBlock, BAND, FailMode.FAIL_OPEN);
        assertEquals(ToolRiskAction.BLOCK, res2.effectiveAction());
        assertTrue(res2.escalated());

        // REQUIRE_APPROVAL escalated to BLOCK
        ToolRiskDecisionPolicy.PolicyResolution res3 =
                ToolRiskDecisionPolicy.resolve(ToolRiskAction.REQUIRE_APPROVAL, opBlock, BAND, FailMode.FAIL_OPEN);
        assertEquals(ToolRiskAction.BLOCK, res3.effectiveAction());
        assertTrue(res3.escalated());

        // Existing REQUIRE_APPROVAL CANNOT be downgraded to ALLOW by engine verdict
        DecisionResult opAllow = createChoiceResult("dec.4", "ALLOW", OptionalDouble.empty());
        ToolRiskDecisionPolicy.PolicyResolution res4 =
                ToolRiskDecisionPolicy.resolve(ToolRiskAction.REQUIRE_APPROVAL, opAllow, BAND, FailMode.FAIL_OPEN);
        assertEquals(ToolRiskAction.REQUIRE_APPROVAL, res4.effectiveAction());
        assertFalse(res4.escalated()); // Preserved existing

        // Existing BLOCK CANNOT be downgraded to REQUIRE_APPROVAL or ALLOW
        ToolRiskDecisionPolicy.PolicyResolution res5 =
                ToolRiskDecisionPolicy.resolve(ToolRiskAction.BLOCK, opReview, BAND, FailMode.FAIL_OPEN);
        assertEquals(ToolRiskAction.BLOCK, res5.effectiveAction());
        assertFalse(res5.escalated());

        ToolRiskDecisionPolicy.PolicyResolution res6 =
                ToolRiskDecisionPolicy.resolve(ToolRiskAction.BLOCK, opAllow, BAND, FailMode.FAIL_OPEN);
        assertEquals(ToolRiskAction.BLOCK, res6.effectiveAction());
        assertFalse(res6.escalated());
    }

    @Test
    void reviewBandRoutesChoiceConfidenceAppropriately() {
        // High confidence above 0.8 -> BLOCK
        DecisionResult highConf = createChoiceResult("dec.band", "BLOCK", OptionalDouble.of(0.9));
        ToolRiskDecisionPolicy.PolicyResolution resHigh =
                ToolRiskDecisionPolicy.resolve(null, highConf, BAND, FailMode.FAIL_OPEN);
        assertEquals(ToolRiskAction.BLOCK, resHigh.effectiveAction());

        // Review band between [0.4, 0.8) -> REQUIRE_APPROVAL (human review)
        DecisionResult midConf = createChoiceResult("dec.band", "BLOCK", OptionalDouble.of(0.6));
        ToolRiskDecisionPolicy.PolicyResolution resMid =
                ToolRiskDecisionPolicy.resolve(null, midConf, BAND, FailMode.FAIL_OPEN);
        assertEquals(ToolRiskAction.REQUIRE_APPROVAL, resMid.effectiveAction());
        assertEquals("REVIEW_BAND_ESCALATION", resMid.reason());

        // Low confidence below 0.4 -> Fallback with LOW_CONFIDENCE
        DecisionResult lowConf = createChoiceResult("dec.band", "BLOCK", OptionalDouble.of(0.2));
        ToolRiskDecisionPolicy.PolicyResolution resLow =
                ToolRiskDecisionPolicy.resolve(null, lowConf, BAND, FailMode.FAIL_OPEN);
        assertTrue(resLow.fallback());
        assertEquals("LOW_CONFIDENCE", resLow.reason());
        assertEquals(ToolRiskAction.REQUIRE_APPROVAL, resLow.effectiveAction());
        assertTrue(resLow.escalated());

        DecisionResult lowConfidenceApproval =
                createChoiceResult("dec.band", "REQUIRE_APPROVAL", OptionalDouble.of(0.2));
        ToolRiskDecisionPolicy.PolicyResolution resLowApproval =
                ToolRiskDecisionPolicy.resolve(null, lowConfidenceApproval, BAND, FailMode.FAIL_OPEN);
        assertEquals(ToolRiskAction.REQUIRE_APPROVAL, resLowApproval.effectiveAction());
        assertTrue(resLowApproval.escalated());
        assertTrue(resLowApproval.fallback());

        // Low confidence below 0.4 under FAIL_CLOSED escalates to REQUIRE_APPROVAL
        ToolRiskDecisionPolicy.PolicyResolution resLowClosed =
                ToolRiskDecisionPolicy.resolve(null, lowConf, BAND, FailMode.FAIL_CLOSED);
        assertTrue(resLowClosed.fallback());
        assertTrue(resLowClosed.escalated());
        assertEquals(ToolRiskAction.REQUIRE_APPROVAL, resLowClosed.effectiveAction());
        assertEquals("LOW_CONFIDENCE", resLowClosed.reason());
    }

    @Test
    void unmappedNoulAndScoreReturnFallbackUnmappedPrimitive() {
        QuestionKey<Noul> noulKey = QuestionKey.noul("p_risk");
        DecisionResult noulResult = DecisionResult.opinionBuilder("dec.noul", 1)
                .put(noulKey, new Noul(0.85))
                .ruleId("rule-noul")
                .build();

        // Fail-open preserves existing decision and marks fallback
        ToolRiskDecisionPolicy.PolicyResolution resOpen =
                ToolRiskDecisionPolicy.resolve(null, noulResult, BAND, FailMode.FAIL_OPEN);
        assertTrue(resOpen.fallback());
        assertEquals("UNMAPPED_PRIMITIVE", resOpen.reason());
        assertNull(resOpen.effectiveAction());

        // Fail-closed escalates to REQUIRE_APPROVAL
        ToolRiskDecisionPolicy.PolicyResolution resClosed =
                ToolRiskDecisionPolicy.resolve(null, noulResult, BAND, FailMode.FAIL_CLOSED);
        assertTrue(resClosed.fallback());
        assertTrue(resClosed.escalated());
        assertEquals(ToolRiskAction.REQUIRE_APPROVAL, resClosed.effectiveAction());
        assertEquals("UNMAPPED_PRIMITIVE", resClosed.reason());

        // Score also falls back to UNMAPPED_PRIMITIVE
        QuestionKey<Score> scoreKey = QuestionKey.score("s_risk");
        DecisionResult scoreResult = DecisionResult.opinionBuilder("dec.score", 1)
                .put(scoreKey, new Score(0.95))
                .ruleId("rule-score")
                .build();
        ToolRiskDecisionPolicy.PolicyResolution resScore =
                ToolRiskDecisionPolicy.resolve(null, scoreResult, BAND, FailMode.FAIL_CLOSED);
        assertTrue(resScore.fallback());
        assertTrue(resScore.escalated());
        assertEquals(ToolRiskAction.REQUIRE_APPROVAL, resScore.effectiveAction());
        assertEquals("UNMAPPED_PRIMITIVE", resScore.reason());
    }

    @Test
    void unrecognizedChoiceLabelTriggersFallbackAndFailsClosed() {
        DecisionResult unknownChoice = createChoiceResult("dec.unknown", "SURPRISE_PAYLOAD", OptionalDouble.of(0.95));

        // Fail-open preserves existing decision
        ToolRiskDecisionPolicy.PolicyResolution resOpen =
                ToolRiskDecisionPolicy.resolve(null, unknownChoice, BAND, FailMode.FAIL_OPEN);
        assertTrue(resOpen.fallback());
        assertEquals("UNRECOGNIZED_CHOICE_LABEL", resOpen.reason());
        assertNull(resOpen.effectiveAction());

        // Fail-closed escalates ALLOW to REQUIRE_APPROVAL
        ToolRiskDecisionPolicy.PolicyResolution resClosed =
                ToolRiskDecisionPolicy.resolve(null, unknownChoice, BAND, FailMode.FAIL_CLOSED);
        assertTrue(resClosed.fallback());
        assertTrue(resClosed.escalated());
        assertEquals(ToolRiskAction.REQUIRE_APPROVAL, resClosed.effectiveAction());
        assertEquals("UNRECOGNIZED_CHOICE_LABEL", resClosed.reason());
    }

    @Test
    void multiQuestionCorrelatesTargetKeyOrComposesMonotonically() {
        QuestionKey<Choice> generalRisk = QuestionKey.choice("general_risk");
        QuestionKey<Choice> leakRisk = QuestionKey.choice("leak_risk");

        DecisionResult multiOpinion = DecisionResult.opinionBuilder("dec.multi", 1)
                .put(generalRisk, new Choice("REQUIRE_APPROVAL"))
                .put(leakRisk, new Choice("BLOCK"))
                .ruleId("multi-rule")
                .build();

        // 1. Target specific key
        ToolRiskDecisionPolicy.PolicyResolution resGeneral =
                ToolRiskDecisionPolicy.resolve(null, multiOpinion, generalRisk, BAND, FailMode.FAIL_OPEN);
        assertEquals(ToolRiskAction.REQUIRE_APPROVAL, resGeneral.effectiveAction());

        ToolRiskDecisionPolicy.PolicyResolution resLeak =
                ToolRiskDecisionPolicy.resolve(null, multiOpinion, leakRisk, BAND, FailMode.FAIL_OPEN);
        assertEquals(ToolRiskAction.BLOCK, resLeak.effectiveAction());

        // 2. Without target key, composes to strongest (BLOCK)
        ToolRiskDecisionPolicy.PolicyResolution resComposed =
                ToolRiskDecisionPolicy.resolve(null, multiOpinion, BAND, FailMode.FAIL_OPEN);
        assertEquals(ToolRiskAction.BLOCK, resComposed.effectiveAction());
    }

    @Test
    void deterministicFallbackUnderFailOpenAndFailClosed() {
        for (ClassifierResult.NoOpinionReason reason : ClassifierResult.NoOpinionReason.values()) {
            DecisionResult.NoOpinion noOp = new DecisionResult.NoOpinion(reason);

            // Fail-open preserves existing decision
            ToolRiskDecisionPolicy.PolicyResolution openAllow =
                    ToolRiskDecisionPolicy.resolve(null, noOp, BAND, FailMode.FAIL_OPEN);
            assertNull(openAllow.effectiveAction());
            assertTrue(openAllow.fallback());
            assertFalse(openAllow.escalated());
            assertEquals(reason.name(), openAllow.reason());

            ToolRiskDecisionPolicy.PolicyResolution openApproval =
                    ToolRiskDecisionPolicy.resolve(ToolRiskAction.REQUIRE_APPROVAL, noOp, BAND, FailMode.FAIL_OPEN);
            assertEquals(ToolRiskAction.REQUIRE_APPROVAL, openApproval.effectiveAction());
            assertTrue(openApproval.fallback());
            assertFalse(openApproval.escalated());

            // Fail-closed escalates ALLOW to REQUIRE_APPROVAL
            ToolRiskDecisionPolicy.PolicyResolution closedAllow =
                    ToolRiskDecisionPolicy.resolve(null, noOp, BAND, FailMode.FAIL_CLOSED);
            assertEquals(ToolRiskAction.REQUIRE_APPROVAL, closedAllow.effectiveAction());
            assertTrue(closedAllow.fallback());
            assertTrue(closedAllow.escalated());
            assertEquals(reason.name(), closedAllow.reason());

            // Fail-closed preserves existing REQUIRE_APPROVAL or BLOCK
            ToolRiskDecisionPolicy.PolicyResolution closedApproval =
                    ToolRiskDecisionPolicy.resolve(ToolRiskAction.REQUIRE_APPROVAL, noOp, BAND, FailMode.FAIL_CLOSED);
            assertEquals(ToolRiskAction.REQUIRE_APPROVAL, closedApproval.effectiveAction());
            assertTrue(closedApproval.fallback());
            assertFalse(closedApproval.escalated());
        }
    }

    @Test
    void conversionToClassifierResultPreservesRestrictionsAndReasonWithoutInventingCertainty() {
        // Active restriction without confidence preserves restrictive Opinion with 0.0f confidence (not fabricating
        // certainty)
        ToolRiskDecisionPolicy.PolicyResolution blockWithoutConf = new ToolRiskDecisionPolicy.PolicyResolution(
                "dec.convert", 1, ToolRiskAction.BLOCK, true, false, "rule-block");
        assertThrows(IllegalStateException.class, () -> ToolRiskDecisionPolicy.toClassifierResult(blockWithoutConf));

        // Fail-closed REQUIRE_APPROVAL without confidence cannot cross the legacy conversion boundary.
        ToolRiskDecisionPolicy.PolicyResolution failClosedApproval = new ToolRiskDecisionPolicy.PolicyResolution(
                "dec.convert", 1, ToolRiskAction.REQUIRE_APPROVAL, true, true, "LOW_CONFIDENCE");
        assertThrows(IllegalStateException.class, () -> ToolRiskDecisionPolicy.toClassifierResult(failClosedApproval));

        // Opinion conversion with confidence preserves value, confidence, and ruleId
        ToolRiskDecisionPolicy.PolicyResolution blockWithConf = new ToolRiskDecisionPolicy.PolicyResolution(
                "dec.convert", 1, ToolRiskAction.BLOCK, OptionalDouble.of(0.95), true, false, "rule-block");
        ClassifierResult<ToolRiskAction> crWithConf = ToolRiskDecisionPolicy.toClassifierResult(blockWithConf);
        assertTrue(crWithConf instanceof ClassifierResult.Opinion);
        var opWithConf = (ClassifierResult.Opinion<ToolRiskAction>) crWithConf;
        assertEquals(ToolRiskAction.BLOCK, opWithConf.value());
        assertEquals(0.95f, opWithConf.confidence(), 1e-4f);
        assertEquals("rule-block", opWithConf.ruleId());

        // Blank ruleId uses fallback ruleId
        ToolRiskDecisionPolicy.PolicyResolution blankRule = new ToolRiskDecisionPolicy.PolicyResolution(
                "dec.convert", 1, ToolRiskAction.BLOCK, OptionalDouble.of(0.95), true, false, "  ");
        ClassifierResult<ToolRiskAction> crBlank = ToolRiskDecisionPolicy.toClassifierResult(blankRule);
        assertTrue(crBlank instanceof ClassifierResult.Opinion);
        assertEquals("TOOL_RISK_POLICY", ((ClassifierResult.Opinion<ToolRiskAction>) crBlank).ruleId());

        // Fallback conversion without effective action preserves NoOpinionReason
        ToolRiskDecisionPolicy.PolicyResolution fbRes =
                new ToolRiskDecisionPolicy.PolicyResolution("dec.convert", 1, null, false, true, "TIMEOUT");
        ClassifierResult<ToolRiskAction> crFb = ToolRiskDecisionPolicy.toClassifierResult(fbRes);
        assertTrue(crFb instanceof ClassifierResult.NoOpinion);
        assertEquals(
                ClassifierResult.NoOpinionReason.TIMEOUT, ((ClassifierResult.NoOpinion<ToolRiskAction>) crFb).reason());
    }

    @Test
    void resolutionProducesBoundedMetadataWithoutPayloads() {
        ToolRiskDecisionPolicy.PolicyResolution res = new ToolRiskDecisionPolicy.PolicyResolution(
                "tool.risk.exec", 2, ToolRiskAction.REQUIRE_APPROVAL, true, false, "RULE_EXEC_SAFETY");

        assertEquals("tool.risk.exec", res.decisionId());
        assertEquals(2, res.schemaVersion());
        assertEquals(ToolRiskAction.REQUIRE_APPROVAL, res.effectiveAction());
        assertTrue(res.escalated());
        assertFalse(res.fallback());
        assertEquals("RULE_EXEC_SAFETY", res.reason());
        assertTrue(res.isRequireApproval());
        assertFalse(res.isAllow());
        assertFalse(res.isBlock());
    }

    private static DecisionResult createChoiceResult(String decId, String label, OptionalDouble conf) {
        QuestionKey<Choice> key = QuestionKey.choice("risk_choice");
        Choice choice = conf.isPresent() ? new Choice(label, conf) : new Choice(label);
        return DecisionResult.opinionBuilder(decId, 1)
                .put(key, choice)
                .ruleId("test-rule")
                .build();
    }
}
