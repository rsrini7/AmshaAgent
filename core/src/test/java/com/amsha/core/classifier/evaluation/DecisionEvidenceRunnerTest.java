package com.amsha.core.classifier.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import com.amsha.classifier.api.DecisionMetadata;
import com.amsha.classifier.api.IntentPolicyResolution;
import com.amsha.classifier.api.StateProjectionPolicy;
import com.amsha.classifier.api.ToolRiskDecisionPolicy.PolicyResolution;
import com.amsha.core.classifier.DecisionToolRiskShadowClassifier;
import com.amsha.core.classifier.selfhosted.StateProjectionRedactor;
import com.amsha.core.config.DecisionProviderConfig;
import com.amsha.core.routing.IntentDecisionRouter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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
        return runnerWith(evaluator, loadShippedCorpus());
    }

    private DecisionEvidenceRunner runnerWith(
            DecisionEvidenceRunner.EvaluationFn evaluator, DecisionEvidenceCorpus corpus) {
        ObjectMapper mapper = strictMapper();
        DecisionEvidenceCorpusRegistry corpusRegistry = new DecisionEvidenceCorpusRegistry(Map.of(1, corpus));
        DecisionPriceScheduleRegistry priceRegistry = new DecisionPriceScheduleRegistry(Map.of());
        DecisionProviderConfig config = Mockito.mock(DecisionProviderConfig.class);
        Mockito.when(config.slots()).thenReturn(Map.of());
        return new DecisionEvidenceRunner(evaluator, corpusRegistry, priceRegistry, mapper, config);
    }

    private static ObjectMapper strictMapper() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    private DecisionEvidenceCorpus loadShippedCorpus() {
        return loadCorpus(strictMapper());
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
        // M1: 11 SECURITY tool-risk cases; 9 of them should block/escalate, all resolved LOW_RISK. The cohort
        // is now complete — every SECURITY case is evaluable — so M1 reports its real rate rather than an
        // incomplete-evidence failure.
        assertEquals(11, summary.m1().denominator());
        assertEquals(9, summary.m1().numerator());
        assertEquals(false, summary.m1().passed());
        assertTrue(summary.m1().note().contains("Evaluated 11/11"), summary.m1().note());
        // M2: all three ambiguous intent cases are evaluated (intent-mal-01 carries a usable message), and the
        // canned evaluator abstains on every intent case, so the cohort is complete with zero errors.
        assertEquals(3, summary.m2().denominator());
        assertEquals(0, summary.m2().numerator());
        assertTrue(summary.m2().passed(), summary.m2().note());
        assertTrue(summary.m2().note().contains("Evaluated 3/3"), summary.m2().note());
        // M3 covers every evaluated case: 12 intent + 13 tool-risk (the five answer-quality cases are out of
        // the declared scope). All 12 intent cases abstain; every tool-risk case returns an opinion.
        assertEquals(
                25,
                summary.m3().denominator(),
                summary.results().stream()
                        .filter(result -> !result.evaluated())
                        .map(result -> result.caseId() + ":" + result.skipReason() + result.transformErrorMessage())
                        .toList()
                        .toString());
        assertEquals(12, summary.m3().numerator());
        assertEquals(true, summary.m3().passed());
        // M4 uses runner-measured elapsed time for both opinions and NoOpinion outcomes.
        assertTrue(summary.m4().containsKey(LANE));
        assertEquals(25, summary.m4().get(LANE).sampleCount());
        assertEquals(25, summary.m4().get(LANE).totalDecisions());
        assertTrue(summary.m4().get(LANE).observedP95Nanos() > 0);
        // M5: no price schedule is registered, so the lane is unpriced.
        assertTrue(summary.m5().containsKey(LANE));
        assertEquals(null, summary.m5().get(LANE).costPerThousand());
    }

    @Test
    void answerQualityCasesAreAReportedScopeExclusionNotASilentSkip() {
        DecisionEvidenceRunner.EvaluationFn worstCase =
                (request, actor) -> DecisionToolRiskShadowClassifier.DECISION_ID.equals(request.decisionId())
                        ? lowRiskOpinion()
                        : abstain();

        DecisionEvidenceSummary summary = runnerWith(worstCase).run(1, "tester");

        List<DecisionEvidenceCaseResult> excluded = summary.results().stream()
                .filter(DecisionEvidenceCaseResult::skippedNoDecision)
                .toList();
        assertEquals(5, excluded.size(), "the five answer-quality cases are excluded from this phase");
        assertTrue(
                excluded.stream().allMatch(r -> DecisionEvidenceCase.TASK_ANSWER_QUALITY.equals(r.task())),
                "only answer-quality cases may be excluded by scope");
        assertTrue(
                excluded.stream()
                        .allMatch(r -> r.skipReason() != null && r.skipReason().contains("declared evaluatedTasks")),
                "the exclusion must be reported as a declared scope, not an ad-hoc skip");
        assertTrue(excluded.stream().noneMatch(DecisionEvidenceCaseResult::evaluated));
    }

    @Test
    void aCorpusDeclaringAnUnevaluableTaskIsRefusedBeforeAnyEgress() {
        // A task declared as evaluated but with no registered decision would skip every one of its cases and
        // report an empty metric as complete — a vacuous pass. The run must refuse instead.
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        DecisionEvidenceCorpus shipped = loadCorpus(mapper);
        DecisionEvidenceCase answerQuality =
                shipped.casesForTask(DecisionEvidenceCase.TASK_ANSWER_QUALITY).getFirst();
        DecisionEvidenceCorpus inconsistent = new DecisionEvidenceCorpus(
                shipped.corpusVersion(),
                shipped.description(),
                shipped.provenance(),
                shipped.baseline(),
                List.of(DecisionEvidenceCase.TASK_INTENT, DecisionEvidenceCase.TASK_ANSWER_QUALITY),
                shipped.redactionReview(),
                shipped.governance(),
                shipped.cases().stream()
                        .filter(c -> DecisionEvidenceCase.TASK_ANSWER_QUALITY.equals(c.task())
                                || DecisionEvidenceCase.TASK_INTENT.equals(c.task()))
                        .toList());
        assertTrue(inconsistent.evaluatesTask(answerQuality.task()));
        DecisionEvidenceRunner runner = runnerWith((request, actor) -> abstain(), inconsistent);

        DecisionEvaluationRefusedException thrown =
                assertThrows(DecisionEvaluationRefusedException.class, () -> runner.run(1, "tester"));
        assertTrue(thrown.getMessage().contains("no decision is registered"), thrown.getMessage());
    }

    @Test
    void anUnregisteredCorpusVersionIsRefused() {
        DecisionEvidenceRunner runner = runnerWith((request, actor) -> abstain());
        assertThrows(DecisionEvaluationRefusedException.class, () -> runner.run(999, "tester"));
    }

    @Test
    void anIntentCaseSendsAUserMessageStateObjectThatTheProjectionAccepts() throws Exception {
        List<DecisionEvaluationRequest> captured = new ArrayList<>();
        DecisionEvidenceRunner.EvaluationFn capture = (request, actor) -> {
            captured.add(request);
            return DecisionToolRiskShadowClassifier.DECISION_ID.equals(request.decisionId())
                    ? lowRiskOpinion()
                    : abstain();
        };

        runnerWith(capture).run(1, "tester");

        DecisionEvaluationRequest intentRequest = captured.stream()
                .filter(r -> IntentDecisionRouter.DECISION_ID.equals(r.decisionId()))
                .findFirst()
                .orElseThrow();

        // The intent state must be a JSON object keyed by the decision's state key, not a bare string. The
        // projection redactor parses the state as a JSON map and fails closed on a scalar, so a bare string
        // would make every intent case abstain against any projection-enforcing provider — silently zeroing
        // the intent half of the evidence run while the gates still read "complete".
        JsonNode state = new ObjectMapper().readTree(intentRequest.stateJson());
        assertTrue(state.isObject(), "the intent state must be a JSON object, got: " + intentRequest.stateJson());
        assertTrue(
                state.has(IntentDecisionRouter.STATE_KEY),
                "the intent state must carry the '" + IntentDecisionRouter.STATE_KEY + "' key");
        assertTrue(state.get(IntentDecisionRouter.STATE_KEY).isTextual());
        assertTrue(state.get(IntentDecisionRouter.STATE_KEY).asText().contains("LRU cache"));

        // Non-vacuity: the same projection a provider applies before egress must accept the state...
        StateProjectionRedactor redactor = new StateProjectionRedactor(new ObjectMapper());
        StateProjectionPolicy policy = StateProjectionPolicy.ofDefault(Set.of(IntentDecisionRouter.STATE_KEY));
        assertTrue(redactor.projectAndRedact(intentRequest.stateJson(), policy).contains("LRU cache"));

        // ...and it must reject the pre-fix shape, which is exactly why the bare string abstained.
        assertThrows(
                IllegalArgumentException.class,
                () -> redactor.projectAndRedact("Please implement a thread-safe LRU cache in Java", policy));
    }
}
