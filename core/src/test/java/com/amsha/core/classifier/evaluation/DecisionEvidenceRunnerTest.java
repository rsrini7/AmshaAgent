package com.amsha.core.classifier.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalDouble;

import com.amsha.classifier.api.DecisionMetadata;
import com.amsha.classifier.api.IntentPolicyResolution;
import com.amsha.classifier.api.ToolRiskDecisionPolicy.PolicyResolution;
import com.amsha.core.classifier.DecisionToolRiskShadowClassifier;
import com.amsha.core.config.DecisionProviderConfig;
import com.amsha.core.routing.IntentDecisionRouter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Exercises the runner end to end against the shipped corpus-v1 (loaded from the classpath) and a canned
 * evaluator, so the request-shaping, the per-case projection, and the M1–M5 metric maths are all checked
 * without any provider egress.
 */
class DecisionEvidenceRunnerTest {

    private static final String LANE = "SELF_HOSTED";
    private static final String PROVIDER = "onnx-local";
    private static final String MODEL = "jev-1.13.0";

    private DecisionEvidenceRunner runnerWith(DecisionEvidenceRunner.EvaluationFn evaluator) {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        DecisionEvidenceCorpus corpus = loadCorpus(mapper);
        DecisionEvidenceCorpusRegistry corpusRegistry = new DecisionEvidenceCorpusRegistry(Map.of(1, corpus));
        DecisionPriceScheduleRegistry priceRegistry = new DecisionPriceScheduleRegistry(Map.of());
        DecisionProviderConfig config = Mockito.mock(DecisionProviderConfig.class);
        Mockito.when(config.slots()).thenReturn(Map.of());
        return new DecisionEvidenceRunner(evaluator, corpusRegistry, priceRegistry, mapper, config);
    }

    private DecisionEvidenceCorpus loadCorpus(ObjectMapper mapper) {
        try (InputStream is = getClass().getResourceAsStream("/decision-evidence/corpus-v1.json")) {
            if (is == null) {
                throw new IllegalStateException("shipped corpus-v1.json is not on the classpath");
            }
            return mapper.readValue(is, DecisionEvidenceCorpus.class);
        } catch (Exception ex) {
            throw new IllegalStateException("could not load corpus-v1 from the classpath", ex);
        }
    }

    /** Every tool-risk case resolves to LOW_RISK (the worst case) and intent cases abstain. */
    private DecisionEvaluationOutcome lowRiskOpinion() {
        Map<String, String> answers = Map.of(DecisionToolRiskShadowClassifier.TOOL_RISK_KEY.name(), "LOW_RISK");
        DecisionMetadata metadata = new DecisionMetadata(PROVIDER, MODEL, Duration.ofMillis(10), 100L, 10L);
        return DecisionEvaluationOutcome.opinion(PROVIDER, LANE, MODEL, null, answers, metadata)
                .withToolRiskPolicyResolution(new PolicyResolution(
                        DecisionToolRiskShadowClassifier.DECISION_ID,
                        1,
                        null,
                        OptionalDouble.empty(),
                        false,
                        false,
                        "TEST_ALLOW"));
    }

    private DecisionEvaluationOutcome abstain() {
        return DecisionEvaluationOutcome.noOpinion(PROVIDER, LANE, MODEL, null, "BASELINE_SELECTED_NO_SPECIALIST")
                .withIntentPolicyResolution(IntentPolicyResolution.fallback(
                        IntentDecisionRouter.DECISION_ID,
                        1,
                        OptionalDouble.empty(),
                        "BASELINE_SELECTED_NO_SPECIALIST"));
    }

    @Test
    void runsEveryShippedCaseAndMeasuresTheGates() {
        DecisionEvidenceRunner.EvaluationFn worstCase =
                (request, actor) -> DecisionToolRiskShadowClassifier.DECISION_ID.equals(request.decisionId())
                        ? lowRiskOpinion()
                        : abstain();

        DecisionEvidenceSummary summary = runnerWith(worstCase).run(1, "tester");

        assertEquals(30, summary.caseCount());
        // M1: 11 SECURITY tool-risk cases; 9 of them should block/escalate, all resolved LOW_RISK.
        assertEquals(11, summary.m1().denominator());
        assertEquals(9, summary.m1().numerator());
        assertEquals(false, summary.m1().passed());
        // M2: one ambiguous case has a blank message and is not evaluated; incomplete evidence fails closed.
        assertEquals(3, summary.m2().denominator());
        assertEquals(0, summary.m2().numerator());
        assertFalse(summary.m2().passed());
        assertTrue(summary.m2().note().contains("Evaluated 2/3"));
        // M3 excludes the malformed intent and tool-risk cases, leaving 23 measured cases and 11 NoOpinions.
        assertEquals(
                23,
                summary.m3().denominator(),
                summary.results().stream()
                        .filter(result -> !result.evaluated())
                        .map(result -> result.caseId() + ":" + result.transformErrorMessage())
                        .toList()
                        .toString());
        assertEquals(11, summary.m3().numerator());
        assertEquals(true, summary.m3().passed());
        // M4 uses runner-measured elapsed time for both opinions and NoOpinion outcomes.
        assertTrue(summary.m4().containsKey(LANE));
        assertEquals(23, summary.m4().get(LANE).sampleCount());
        assertEquals(23, summary.m4().get(LANE).totalDecisions());
        assertTrue(summary.m4().get(LANE).observedP95Nanos() > 0);
        // M5: no price schedule is registered, so the lane is unpriced.
        assertTrue(summary.m5().containsKey(LANE));
        assertEquals(null, summary.m5().get(LANE).costPerThousand());
    }

    @Test
    void anUnregisteredCorpusVersionIsRefused() {
        DecisionEvidenceRunner runner = runnerWith((request, actor) -> abstain());
        assertThrows(DecisionEvaluationRefusedException.class, () -> runner.run(999, "tester"));
    }
}
