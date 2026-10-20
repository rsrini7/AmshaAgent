package com.amsha.core.classifier.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.classifier.api.DecisionMetadata;
import com.amsha.classifier.api.IntentPolicyResolution;
import com.amsha.classifier.api.StateProjectionPolicy;
import com.amsha.classifier.api.ToolRiskDecisionPolicy.PolicyResolution;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.amsha.core.classifier.DecisionToolRiskShadowClassifier;
import com.amsha.core.classifier.selfhosted.StateProjectionRedactor;
import com.amsha.core.classifier.wire.WireProfile;
import com.amsha.core.config.DecisionProviderConfig;
import com.amsha.core.routing.IntentDecisionRouter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Exercises the runner end to end against the shipped corpus-v3 (loaded from the classpath) and a canned
 * evaluator, so the request-shaping, the per-case projection, and the M1–M5 metric maths are all checked
 * without any provider egress.
 */
class DecisionEvidenceRunnerTest {

    private static final String LANE = "SELF_HOSTED";
    private static final String PROVIDER = "onnx-local";
    private static final String MODEL = "jev-1.13.0";

    private DecisionEvidenceCorpus withInput(
            DecisionEvidenceCorpus source, String targetId, Map<String, Object> input, List<String> evaluatedTasks) {
        List<DecisionEvidenceCase> cases = new ArrayList<>(source.cases().size());
        boolean replaced = false;
        for (DecisionEvidenceCase evidenceCase : source.cases()) {
            if (targetId.equals(evidenceCase.id())) {
                cases.add(new DecisionEvidenceCase(
                        evidenceCase.id(),
                        evidenceCase.task(),
                        evidenceCase.partition(),
                        evidenceCase.expectedLabel(),
                        evidenceCase.acceptableLabels(),
                        evidenceCase.riskCategory(),
                        evidenceCase.baselineAmbiguity(),
                        evidenceCase.ambiguityRationale(),
                        evidenceCase.criteria(),
                        evidenceCase.options(),
                        input,
                        evidenceCase.notes()));
                replaced = true;
            } else {
                cases.add(evidenceCase);
            }
        }
        if (!replaced) {
            throw new IllegalArgumentException("target case was not present in corpus");
        }
        return new DecisionEvidenceCorpus(
                source.corpusVersion(),
                source.description(),
                source.provenance(),
                source.baseline(),
                evaluatedTasks,
                source.redactionReview(),
                source.governance(),
                cases);
    }

    private DecisionEvidenceRunner runnerWith(DecisionEvidenceRunner.EvaluationFn evaluator) {
        return runnerWith(evaluator, loadShippedCorpus());
    }

    private DecisionEvidenceRunner runnerWith(
            DecisionEvidenceRunner.EvaluationFn evaluator, DecisionEvidenceCorpus corpus) {
        ObjectMapper mapper = strictMapper();
        DecisionEvidenceCorpusRegistry corpusRegistry =
                new DecisionEvidenceCorpusRegistry(Map.of(corpus.corpusVersion(), corpus));
        DecisionPriceScheduleRegistry priceRegistry = new DecisionPriceScheduleRegistry(Map.of());
        DecisionProviderConfig config = Mockito.mock(DecisionProviderConfig.class);
        Mockito.when(config.slots()).thenReturn(Map.of());
        return new DecisionEvidenceRunner(evaluator, corpusRegistry, priceRegistry, mapper, config);
    }

    private static ObjectMapper strictMapper() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    private DecisionEvidenceCorpus loadShippedCorpus() {
        return loadCorpus(strictMapper(), 3);
    }

    private DecisionEvidenceCorpus loadCorpus(ObjectMapper mapper) {
        return loadCorpus(mapper, 3);
    }

    private DecisionEvidenceCorpus loadHistoricalCorpus() {
        return loadCorpus(strictMapper(), 1);
    }

    private DecisionEvidenceCorpus loadCorpus(ObjectMapper mapper, int version) {
        String resource = "/decision-evidence/corpus-v" + version + ".json";
        try (InputStream is = getClass().getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("shipped corpus-v" + version + ".json is not on the classpath");
            }
            return mapper.readValue(is, DecisionEvidenceCorpus.class);
        } catch (Exception ex) {
            throw new IllegalStateException("could not load corpus-v" + version + " from the classpath", ex);
        }
    }

    private DecisionEvaluationOutcome lowRiskOpinion() {
        Map<String, String> answers = Map.of(DecisionToolRiskShadowClassifier.TOOL_RISK_KEY.name(), "LOW_RISK");
        DecisionMetadata metadata = new DecisionMetadata(PROVIDER, MODEL, Duration.ofMillis(10), 100L, 10L);
        return DecisionEvaluationOutcome.opinion(PROVIDER, LANE, MODEL, null, answers, metadata)
                .withToolRiskPolicyResolution(new PolicyResolution(
                        DecisionToolRiskShadowClassifier.DECISION_ID,
                        DecisionToolRiskShadowClassifier.SCHEMA_VERSION,
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
    void refusesToolRiskEvidenceBeforeEgressWhenProjectionOmitsRequiredOperation() {
        DecisionProviderConfig config = Mockito.mock(DecisionProviderConfig.class);
        DecisionProviderConfig.Slot slot = Mockito.mock(DecisionProviderConfig.Slot.class);
        DecisionProviderConfig.Slot.Governance governance = Mockito.mock(DecisionProviderConfig.Slot.Governance.class);
        Mockito.when(config.selected()).thenReturn(Optional.of("omlx"));
        Mockito.when(config.slots()).thenReturn(Map.of("omlx", slot));
        Mockito.when(slot.enabled()).thenReturn(true);
        Mockito.when(slot.endpoint()).thenReturn(Optional.of("http://127.0.0.1:8000/v1/systemone"));
        Mockito.when(slot.model()).thenReturn(Optional.of("clef-flash-oQ4e"));
        Mockito.when(slot.apiKey()).thenReturn(Optional.empty());
        Mockito.when(slot.timeout()).thenReturn(Duration.ofSeconds(50));
        Mockito.when(slot.wireProfile()).thenReturn(WireProfile.JSON_OBJECT);
        Mockito.when(slot.governance()).thenReturn(governance);
        Mockito.when(governance.dataLocation()).thenReturn(Optional.empty());
        Mockito.when(governance.retentionWindow()).thenReturn(Duration.ZERO);
        Mockito.when(governance.allowTrainingUse()).thenReturn(false);
        Mockito.when(governance.tenantIsolation()).thenReturn(true);
        Mockito.when(governance.projectionAllowList())
                .thenReturn(Optional.of(List.of("agentRole", "autonomyLevel", "toolName", "parameters", "goal")));
        Mockito.when(governance.costCeilingPerThousand()).thenReturn(Optional.empty());

        AtomicInteger providerCalls = new AtomicInteger();
        DecisionEvidenceCorpus corpus = loadShippedCorpus();
        DecisionEvidenceRunner runner = new DecisionEvidenceRunner(
                (request, actor) -> {
                    providerCalls.incrementAndGet();
                    return abstain();
                },
                new DecisionEvidenceCorpusRegistry(Map.of(corpus.corpusVersion(), corpus)),
                new DecisionPriceScheduleRegistry(Map.of()),
                strictMapper(),
                config);

        DecisionEvaluationRefusedException refusal =
                assertThrows(DecisionEvaluationRefusedException.class, () -> runner.run(3, "reviewer"));

        assertEquals(DecisionRefusalReason.CORPUS_NOT_MEASURABLE, refusal.reason());
        assertTrue(refusal.getMessage().contains("requiredOperation"));
        assertTrue(refusal.getMessage().contains("No provider egress performed"));
        assertEquals(0, providerCalls.get(), "a missing projected operation must be refused before egress");
    }

    @Test
    void runsEveryShippedCaseAndMeasuresTheGates() {
        DecisionEvidenceRunner.EvaluationFn worstCase =
                (request, actor) -> DecisionToolRiskShadowClassifier.DECISION_ID.equals(request.decisionId())
                        ? lowRiskOpinion()
                        : abstain();

        DecisionEvidenceSummary summary = runnerWith(worstCase).run(3, "tester");

        assertEquals(30, summary.caseCount());
        // M1: 11 SECURITY tool-risk cases; 10 rubric-aligned restrictive cases all resolve LOW_RISK.
        // is now complete — every SECURITY case is evaluable — so M1 reports its real rate rather than an
        // incomplete-evidence failure.
        assertEquals(11, summary.m1().denominator());
        assertEquals(10, summary.m1().numerator());
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

        DecisionEvidenceSummary summary = runnerWith(worstCase).run(3, "tester");

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
    void everyRunIsAttributedAndProjectsDiagnosticsOnlyOnRequest() {
        DecisionEvidenceRunner.EvaluationFn worstCase =
                (request, actor) -> DecisionToolRiskShadowClassifier.DECISION_ID.equals(request.decisionId())
                        ? lowRiskOpinion()
                        : abstain();

        DecisionEvidenceSummary aggregateOnly = runnerWith(worstCase).run(3, "tester");

        String runId = aggregateOnly.attribution().runId();
        assertTrue(runId != null && !runId.isBlank(), "every run must carry its own identifier");
        // The mocked provider config selects nothing, so the attribution records the absence rather than
        // inventing a provider — which is what makes a run whose cases were all refused self-describing.
        assertEquals(null, aggregateOnly.attribution().providerId());
        assertEquals(null, aggregateOnly.attribution().configuredModel());
        assertEquals(null, aggregateOnly.attribution().lane());
        assertFalse(aggregateOnly.diagnosticsIncluded());
        assertEquals(null, aggregateOnly.diagnostics());

        DecisionEvidenceSummary withDiagnostics = runnerWith(worstCase).run(3, "tester", true);

        assertTrue(withDiagnostics.diagnosticsIncluded());
        DecisionEvidenceDiagnostics diagnostics = withDiagnostics.diagnostics();
        assertEquals(25, diagnostics.evaluatedCaseCount());
        assertEquals(0, diagnostics.refusedCaseCount());
        assertEquals(0, diagnostics.transformErrorCaseCount());
        assertEquals(5, diagnostics.skippedCaseCount());
        assertEquals(1, diagnostics.providerUsage().size());

        DecisionEvidenceDiagnostics.ProviderUsage usage =
                diagnostics.providerUsage().getFirst();
        assertEquals(PROVIDER, usage.providerId());
        assertEquals(MODEL, usage.model());
        assertEquals(LANE, usage.lane());
        assertEquals(25, usage.evaluatedCaseCount());
        assertTrue(usage.inputTokenReportedCaseCount() > 0);
        assertEquals(usage.inputTokenReportedCaseCount() * 100L, usage.totalInputTokens());
        assertEquals(usage.outputTokenReportedCaseCount() * 10L, usage.totalOutputTokens());
    }

    @Test
    void twoRunsOfTheSameCorpusAreDistinguishableByTheirRunId() {
        DecisionEvidenceRunner.EvaluationFn worstCase =
                (request, actor) -> DecisionToolRiskShadowClassifier.DECISION_ID.equals(request.decisionId())
                        ? lowRiskOpinion()
                        : abstain();

        String first = runnerWith(worstCase).run(3, "tester").attribution().runId();
        String second = runnerWith(worstCase).run(3, "tester").attribution().runId();

        assertNotEquals(first, second, "two runs of the same corpus must not share a run identifier");
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

        DecisionEvaluationRefusedException thrown = assertThrows(
                DecisionEvaluationRefusedException.class, () -> runner.run(shipped.corpusVersion(), "tester"));
        assertTrue(thrown.getMessage().contains("no decision is registered"), thrown.getMessage());
    }

    @Test
    void historicalVersionOneIsRefusedWhileEvaluatedCasesCarryUndeliveredCriteria() {
        // Five shipped cases carry case-level criteria that the evidence path never sends (the shadow
        // comparison harness appends it to the goal/message; DecisionEvaluationRequest carries state and
        // options, not policy). Measuring M1/M2 on them would score that prompt difference rather than the
        // model, so the run is refused outright rather than silently ignoring the field. This guard is what
        // forces the versioned re-scope; it must keep failing closed until the corpus is re-scoped.
        DecisionEvidenceRunner runner = runnerWith((request, actor) -> abstain(), loadHistoricalCorpus());

        DecisionEvaluationRefusedException thrown =
                assertThrows(DecisionEvaluationRefusedException.class, () -> runner.run(1, "tester"));

        assertEquals(DecisionRefusalReason.CORPUS_NOT_MEASURABLE, thrown.reason());
        assertTrue(thrown.getMessage().contains("intent-contra-01"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("toolrisk-contra-01"), thrown.getMessage());
    }

    @Test
    void anUnregisteredCorpusVersionIsRefused() {
        DecisionEvidenceRunner runner = runnerWith((request, actor) -> abstain());
        DecisionEvaluationRefusedException refusal =
                assertThrows(DecisionEvaluationRefusedException.class, () -> runner.run(999, "tester"));

        assertEquals(DecisionRefusalReason.CORPUS_NOT_MEASURABLE, refusal.reason());
        assertTrue(refusal.getMessage().contains("999"));
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

        runnerWith(capture).run(3, "tester");

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

    @Test
    void everyToolRiskRequestCarriesCanonicalOperations() throws Exception {
        List<DecisionEvaluationRequest> captured = new ArrayList<>();
        DecisionEvidenceRunner.EvaluationFn capture = (request, actor) -> {
            captured.add(request);
            return DecisionToolRiskShadowClassifier.DECISION_ID.equals(request.decisionId())
                    ? lowRiskOpinion()
                    : abstain();
        };

        runnerWith(capture).run(3, "tester");

        List<JsonNode> states = new ArrayList<>();
        for (DecisionEvaluationRequest request : captured) {
            if (DecisionToolRiskShadowClassifier.DECISION_ID.equals(request.decisionId())) {
                states.add(strictMapper().readTree(request.stateJson()));
            }
        }
        assertEquals(13, states.size());
        List<String> operations = new ArrayList<>();
        for (JsonNode state : states) {
            assertTrue(state.hasNonNull(ToolRiskShadowContext.REQUIRED_OPERATION_FIELD));
            String operation =
                    state.get(ToolRiskShadowContext.REQUIRED_OPERATION_FIELD).asText();
            assertFalse(operation.isBlank());
            operations.add(operation);
        }
        assertTrue(operations.contains("EXECUTE_SHELL"));
        assertTrue(operations.contains("NETWORK_OUTBOUND"));
    }

    @Test
    void aVersionThreeCorpusMissingAnOperationIsRefusedBeforeEgress() throws Exception {
        DecisionEvidenceCorpus shipped = loadShippedCorpus();
        List<DecisionEvidenceCase> incompleteCases =
                new ArrayList<>(shipped.cases().size());
        for (DecisionEvidenceCase evidenceCase : shipped.cases()) {
            if (!"toolrisk-dup-01".equals(evidenceCase.id())) {
                incompleteCases.add(evidenceCase);
                continue;
            }
            Map<String, Object> input = new LinkedHashMap<>(evidenceCase.input());
            input.remove(ToolRiskShadowContext.REQUIRED_OPERATION_INPUT_KEY);
            incompleteCases.add(new DecisionEvidenceCase(
                    evidenceCase.id(),
                    evidenceCase.task(),
                    evidenceCase.partition(),
                    evidenceCase.expectedLabel(),
                    evidenceCase.acceptableLabels(),
                    evidenceCase.riskCategory(),
                    evidenceCase.baselineAmbiguity(),
                    evidenceCase.ambiguityRationale(),
                    evidenceCase.criteria(),
                    evidenceCase.options(),
                    input,
                    evidenceCase.notes()));
        }
        DecisionEvidenceCorpus incomplete = new DecisionEvidenceCorpus(
                shipped.corpusVersion(),
                shipped.description(),
                shipped.provenance(),
                shipped.baseline(),
                shipped.evaluatedTasks(),
                shipped.redactionReview(),
                shipped.governance(),
                incompleteCases);
        List<DecisionEvaluationRequest> egress = new ArrayList<>();
        DecisionEvidenceRunner runner = runnerWith(
                (request, actor) -> {
                    egress.add(request);
                    return abstain();
                },
                incomplete);

        DecisionEvaluationRefusedException refusal =
                assertThrows(DecisionEvaluationRefusedException.class, () -> runner.run(3, "tester"));

        assertEquals(DecisionRefusalReason.CORPUS_NOT_MEASURABLE, refusal.reason());
        assertTrue(egress.isEmpty(), "an incomplete corpus must be rejected before provider egress");
    }

    @Test
    void invalidOperationValueIsRefusedBeforeEgress() throws Exception {
        DecisionEvidenceCorpus shipped = loadShippedCorpus();
        DecisionEvidenceCase target = shipped.cases().stream()
                .filter(evidenceCase -> "toolrisk-dup-01".equals(evidenceCase.id()))
                .findFirst()
                .orElseThrow();
        Map<String, Object> input = new LinkedHashMap<>(target.input());
        input.put(ToolRiskShadowContext.REQUIRED_OPERATION_INPUT_KEY, "NOT_AN_OPERATION");
        DecisionEvidenceCorpus invalid = withInput(shipped, target.id(), input, shipped.evaluatedTasks());
        List<DecisionEvaluationRequest> egress = new ArrayList<>();
        DecisionEvidenceRunner runner = runnerWith(
                (request, actor) -> {
                    egress.add(request);
                    return abstain();
                },
                invalid);

        DecisionEvaluationRefusedException refusal =
                assertThrows(DecisionEvaluationRefusedException.class, () -> runner.run(3, "tester"));

        assertEquals(DecisionRefusalReason.CORPUS_NOT_MEASURABLE, refusal.reason());
        assertTrue(refusal.getMessage().contains("invalid required operation"));
        assertTrue(egress.isEmpty(), "invalid operation metadata must be refused before provider egress");
    }

    @Test
    void unknownLegacyToolNameIsRefusedBeforeEgress() throws Exception {
        DecisionEvidenceCorpus shipped = loadCorpus(strictMapper(), 2);
        DecisionEvidenceCase target = shipped.cases().stream()
                .filter(evidenceCase -> "toolrisk-dup-01".equals(evidenceCase.id()))
                .findFirst()
                .orElseThrow();
        Map<String, Object> input = new LinkedHashMap<>(target.input());
        input.put("tool_name", "custom:unmapped");
        DecisionEvidenceCorpus invalid = withInput(shipped, target.id(), input, shipped.evaluatedTasks());
        List<DecisionEvaluationRequest> egress = new ArrayList<>();
        DecisionEvidenceRunner runner = runnerWith(
                (request, actor) -> {
                    egress.add(request);
                    return abstain();
                },
                invalid);

        DecisionEvaluationRefusedException refusal =
                assertThrows(DecisionEvaluationRefusedException.class, () -> runner.run(2, "tester"));

        assertEquals(DecisionRefusalReason.CORPUS_NOT_MEASURABLE, refusal.reason());
        assertTrue(refusal.getMessage().contains("unmapped legacy tool name"));
        assertTrue(egress.isEmpty(), "unmapped legacy operation must be refused before provider egress");
    }

    @Test
    void excludedToolRiskCasesDoNotBlockAnIntentOnlyCorpus() throws Exception {
        DecisionEvidenceCorpus shipped = loadShippedCorpus();
        DecisionEvidenceCase target = shipped.cases().stream()
                .filter(evidenceCase -> "toolrisk-dup-01".equals(evidenceCase.id()))
                .findFirst()
                .orElseThrow();
        Map<String, Object> input = new LinkedHashMap<>(target.input());
        input.put(ToolRiskShadowContext.REQUIRED_OPERATION_INPUT_KEY, "NOT_AN_OPERATION");
        DecisionEvidenceCorpus intentOnly =
                withInput(shipped, target.id(), input, List.of(DecisionEvidenceCase.TASK_INTENT));
        List<DecisionEvaluationRequest> egress = new ArrayList<>();
        DecisionEvidenceRunner runner = runnerWith(
                (request, actor) -> {
                    egress.add(request);
                    return abstain();
                },
                intentOnly);

        runner.run(3, "tester");

        assertTrue(egress.stream()
                .noneMatch(request -> DecisionToolRiskShadowClassifier.DECISION_ID.equals(request.decisionId())));
    }

    @Test
    void legacyCorpusVersionsMapKnownToolNamesToRequiredOperations() throws Exception {
        List<DecisionEvaluationRequest> captured = new ArrayList<>();
        DecisionEvidenceRunner.EvaluationFn capture = (request, actor) -> {
            captured.add(request);
            return DecisionToolRiskShadowClassifier.DECISION_ID.equals(request.decisionId())
                    ? lowRiskOpinion()
                    : abstain();
        };
        DecisionEvidenceRunner runner = runnerWith(capture, loadCorpus(strictMapper(), 2));

        runner.run(2, "tester");

        List<JsonNode> toolRiskStates = new ArrayList<>();
        for (DecisionEvaluationRequest request : captured) {
            if (DecisionToolRiskShadowClassifier.DECISION_ID.equals(request.decisionId())) {
                toolRiskStates.add(strictMapper().readTree(request.stateJson()));
            }
        }
        assertEquals(13, toolRiskStates.size());
        assertTrue(
                toolRiskStates.stream().allMatch(state -> state.has(ToolRiskShadowContext.REQUIRED_OPERATION_FIELD)));
    }
}
