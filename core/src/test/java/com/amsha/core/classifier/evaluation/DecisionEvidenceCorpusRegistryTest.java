package com.amsha.core.classifier.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Contract for the versioned evidence corpus and its registry.
 *
 * <p>The happy path asserts against the corpus this build actually ships, so a damaged resource fails
 * here rather than at runtime. The refusal cases construct records directly: they are testing the
 * validation rules, and going through Jackson would wrap the record's own exception in a mapping
 * exception and hide which rule fired.
 */
class DecisionEvidenceCorpusRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void loadsTheShippedCorpusFromTheClasspath() {
        DecisionEvidenceCorpusRegistry registry = new DecisionEvidenceCorpusRegistry(MAPPER);

        assertTrue(registry.registeredVersions().contains(1), "corpus v1 must be registered");
        assertTrue(registry.registeredVersions().contains(2), "corpus v2 must be registered");
        DecisionEvidenceCorpus corpus = registry.find(1).orElseThrow();
        assertEquals(1, corpus.corpusVersion());
        assertEquals(30, corpus.caseCount(), "the shipped corpus carries 30 redacted cases");
        assertEquals(
                9,
                corpus.cases().stream()
                        .map(DecisionEvidenceCase::partition)
                        .distinct()
                        .count(),
                "the shipped corpus spans all nine partitions");
        assertFalse(corpus.casesForTask(DecisionEvidenceCase.TASK_INTENT).isEmpty(), "intent cases present");
        assertFalse(corpus.casesForTask(DecisionEvidenceCase.TASK_TOOL_RISK).isEmpty(), "tool-risk cases present");
        assertTrue(
                corpus.cases().stream().anyMatch(DecisionEvidenceCase::isSecurityRelevant), "security cases present");
    }

    @Test
    void versionTwoIsRunnableAgainstRegisteredCriteriaAndKeepsTheFrozenM2Cohort() {
        DecisionEvidenceCorpus corpus =
                new DecisionEvidenceCorpusRegistry(MAPPER).find(2).orElseThrow();

        assertEquals(2, corpus.corpusVersion());
        assertEquals(30, corpus.caseCount());
        assertEquals(
                List.of(DecisionEvidenceCase.TASK_INTENT, DecisionEvidenceCase.TASK_TOOL_RISK),
                corpus.evaluatedTasks());

        List<DecisionEvidenceCase> intentCases = corpus.casesForTask(DecisionEvidenceCase.TASK_INTENT);
        List<DecisionEvidenceCase> toolRiskCases = corpus.casesForTask(DecisionEvidenceCase.TASK_TOOL_RISK);
        assertEquals(
                3,
                intentCases.stream()
                        .filter(DecisionEvidenceCase::isBaselineAmbiguous)
                        .count(),
                "the M2 denominator remains frozen at three ambiguous intent cases");
        for (String caseId : List.of("intent-underspecified-01", "intent-hostile-01", "intent-mal-01")) {
            assertTrue(intentCases.stream().anyMatch(c -> c.id().equals(caseId)), caseId);
        }
        assertTrue(
                intentCases.stream()
                        .noneMatch(c -> c.criteria() != null && !c.criteria().isBlank()),
                "evaluated intent cases must use the registered intent criteria");
        assertTrue(
                toolRiskCases.stream()
                        .noneMatch(c -> c.criteria() != null && !c.criteria().isBlank()),
                "evaluated tool-risk cases must use the registered tool-risk criteria");

        List<DecisionEvidenceCase> answerQualityCases = corpus.casesForTask(DecisionEvidenceCase.TASK_ANSWER_QUALITY);
        assertTrue(
                answerQualityCases.stream()
                        .anyMatch(c -> c.criteria() != null && !c.criteria().isBlank()),
                "out-of-scope answer-quality criteria remain descriptive corpus data");
        assertEquals(
                11,
                toolRiskCases.stream()
                        .filter(DecisionEvidenceCase::isSecurityRelevant)
                        .count(),
                "M1's SECURITY denominator remains eleven cases");
        assertEquals(
                10,
                toolRiskCases.stream()
                        .filter(DecisionEvidenceCase::isSecurityRelevant)
                        .filter(c -> "BLOCK".equals(c.expectedLabel()) || "REQUIRE_APPROVAL".equals(c.expectedLabel()))
                        .count(),
                "the rubric-aligned v2 corpus treats every shell invocation as restrictive");
    }

    @Test
    void refusesUnknownCorpusFieldsEvenWhenInjectedMapperIsLenient() throws IOException {
        String resourcePath = DecisionEvidenceCorpusRegistry.RESOURCE_DIR + "/corpus-v1.json";
        byte[] resource = DecisionRegistryTestSupport.resourceWithUnknownField(resourcePath, "unexpectedCorpusField");

        DecisionEvidenceCorpusRegistry registry = DecisionRegistryTestSupport.withResource(
                resourcePath,
                resource,
                () -> new DecisionEvidenceCorpusRegistry(DecisionRegistryTestSupport.lenientMapper()));

        assertTrue(registry.find(1).isEmpty(), "a corpus with an unsupported field must not be registered");
    }

    @Test
    void theShippedCorpusPinsItsBaselineAndClassifiesEveryIntentCase() {
        DecisionEvidenceCorpus corpus =
                new DecisionEvidenceCorpusRegistry(MAPPER).find(1).orElseThrow();

        assertEquals("amsha.deterministic.intent-baseline", corpus.baseline().baselineId(), "the baseline is named");
        assertEquals(1, corpus.baseline().baselineVersion(), "the baseline is versioned");
        assertFalse(corpus.baseline().description().isBlank(), "the baseline declares how ambiguity is judged");

        List<DecisionEvidenceCase> intentCases = corpus.casesForTask(DecisionEvidenceCase.TASK_INTENT);
        assertTrue(
                intentCases.stream().allMatch(c -> c.baselineAmbiguity() != null),
                "every intent case must be classified, or M2's denominator is undefined");
        assertTrue(
                intentCases.stream()
                        .allMatch(c -> c.ambiguityRationale() != null
                                && !c.ambiguityRationale().isBlank()),
                "every classification must carry its rationale");

        long ambiguous = intentCases.stream()
                .filter(DecisionEvidenceCase::isBaselineAmbiguous)
                .count();
        assertEquals(3, ambiguous, "the shipped corpus has three cases the baseline cannot resolve");
        assertTrue(ambiguous < intentCases.size(), "the ambiguous subset must be a strict subset");

        assertTrue(
                corpus.cases().stream()
                        .filter(c -> !DecisionEvidenceCase.TASK_INTENT.equals(c.task()))
                        .noneMatch(c -> c.baselineAmbiguity() != null),
                "only intent cases may carry a baseline classification");
    }

    @Test
    void theShippedCorpusDeclaresItsEvaluatedScopeAndEveryIntentShortlist() {
        DecisionEvidenceCorpus corpus =
                new DecisionEvidenceCorpusRegistry(MAPPER).find(1).orElseThrow();

        // The scope is declared, not inferred: answer-quality cases are an explicit exclusion this phase
        // does not evaluate, so the run reports them rather than skipping them silently.
        assertEquals(
                List.of(DecisionEvidenceCase.TASK_INTENT, DecisionEvidenceCase.TASK_TOOL_RISK),
                corpus.evaluatedTasks(),
                "corpus v1 is evaluated for intent and tool-risk only");
        assertFalse(corpus.evaluatesTask(DecisionEvidenceCase.TASK_ANSWER_QUALITY), "answer-quality is out of scope");
        assertTrue(corpus.evaluatesTask(DecisionEvidenceCase.TASK_INTENT), "intent is in scope");
        assertTrue(corpus.evaluatesTask(DecisionEvidenceCase.TASK_TOOL_RISK), "tool-risk is in scope");

        // Every intent case owns its shortlist, and every label it expects is one it offers — so the corpus
        // can be revised without the runner's behaviour drifting underneath it.
        List<DecisionEvidenceCase> intentCases = corpus.casesForTask(DecisionEvidenceCase.TASK_INTENT);
        assertTrue(
                intentCases.stream()
                        .allMatch(c -> c.options() != null && !c.options().isEmpty()),
                "every intent case must declare the shortlist it is routed over");
        for (DecisionEvidenceCase c : intentCases) {
            assertTrue(
                    c.options().contains(c.expectedLabel()) || "NO_OPINION".equals(c.expectedLabel()),
                    c.id() + " expects " + c.expectedLabel() + " but offers " + c.options());
            for (String acceptable : c.acceptableLabels()) {
                assertTrue(
                        c.options().contains(acceptable) || "NO_OPINION".equals(acceptable),
                        c.id() + " accepts " + acceptable + " but offers " + c.options());
            }
        }
    }

    @Test
    void anUnshippedVersionIsAbsentRatherThanInvented() {
        DecisionEvidenceCorpusRegistry registry = new DecisionEvidenceCorpusRegistry(MAPPER);

        assertTrue(registry.find(99).isEmpty(), "a version this build does not ship must be absent");
    }

    @Test
    void findsTheShippedCorpusEvenWhenTheContextClassLoaderCannot() {
        // A native image or container thread can carry a context classloader that is present yet blind to a
        // resource this build ships. The registry must fall back to its own classloader rather than report
        // the corpus as missing, which would leave the evidence endpoint refusing every request.
        String resourcePath = DecisionEvidenceCorpusRegistry.RESOURCE_DIR + "/corpus-v1.json";

        DecisionEvidenceCorpusRegistry registry = DecisionRegistryTestSupport.withResourceHidden(
                resourcePath, () -> new DecisionEvidenceCorpusRegistry(MAPPER));

        assertTrue(
                registry.registeredVersions().contains(1),
                "a context classloader that misses must not hide a corpus the registry itself ships");
    }

    @Test
    void refusesARedactionReviewThatIsNotApproved() {
        DecisionEvidenceCorpus.RedactionReview pending =
                new DecisionEvidenceCorpus.RedactionReview("PENDING", "2026-10-07T00:00:00Z", "verifier", false, false);

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> corpusWith(pending, List.of(validCase("intent-std-01"))));
        assertTrue(thrown.getMessage().contains("not APPROVED"), thrown.getMessage());
    }

    @Test
    void refusesACorpusThatAdmitsToCarryingRealSecretsOrPii() {
        DecisionEvidenceCorpus.RedactionReview secrets =
                new DecisionEvidenceCorpus.RedactionReview("APPROVED", "2026-10-07T00:00:00Z", "verifier", true, false);
        DecisionEvidenceCorpus.RedactionReview pii =
                new DecisionEvidenceCorpus.RedactionReview("APPROVED", "2026-10-07T00:00:00Z", "verifier", false, true);

        assertThrows(IllegalArgumentException.class, () -> corpusWith(secrets, List.of(validCase("intent-std-01"))));
        assertThrows(IllegalArgumentException.class, () -> corpusWith(pii, List.of(validCase("intent-std-01"))));
    }

    @Test
    void refusesAnEmptyCorpus() {
        assertThrows(IllegalArgumentException.class, () -> corpusWith(approved(), List.of()));
    }

    @Test
    void refusesACorpusAboveTheCaseBound() {
        List<DecisionEvidenceCase> oversized = new ArrayList<>();
        IntStream.rangeClosed(0, DecisionEvidenceCorpus.MAX_CASES)
                .forEach(i -> oversized.add(validCase("intent-std-" + i)));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> corpusWith(approved(), oversized));
        assertTrue(thrown.getMessage().contains("above the"), thrown.getMessage());
    }

    @Test
    void refusesDuplicateCaseIdentifiers() {
        List<DecisionEvidenceCase> duplicated = List.of(validCase("intent-std-01"), validCase("intent-std-01"));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> corpusWith(approved(), duplicated));
        assertTrue(thrown.getMessage().contains("duplicate"), thrown.getMessage());
    }

    @Test
    void refusesACaseWithAnUnknownTaskOrPartition() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvidenceCase(
                        "x",
                        "mystery",
                        "standard",
                        "coder",
                        List.of(),
                        "BENIGN",
                        null,
                        null,
                        null,
                        List.of(),
                        Map.of(),
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvidenceCase(
                        "x", "intent", "mystery", "coder", List.of(), "BENIGN", null, null, null, List.of(), Map.of(),
                        null));
    }

    @Test
    void refusesACaseWithAnUnknownRiskCategory() {
        // A misspelled or unsupported category must fail closed, not be silently counted as non-security and
        // shrink M1's denominator.
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvidenceCase(
                        "x",
                        "intent",
                        "standard",
                        "coder",
                        List.of(),
                        "TOP_SECRET",
                        DecisionEvidenceCase.BASELINE_UNAMBIGUOUS,
                        "reason",
                        null,
                        List.of(),
                        Map.of(),
                        null));
    }

    @Test
    void aMisspelledOrUnsupportedFieldIsNotSilentlyDiscarded() {
        // The app's ObjectMapper is globally configured to ignore unknown properties. The corpus and price
        // registries therefore do NOT rely on a per-class @JsonIgnoreProperties to stay strict — that does not
        // override the global lenient mapper in this Jackson version. Instead each registry parses these
        // versioned metric inputs with a copy of the mapper reconfigured to FAIL_ON_UNKNOWN_PROPERTIES=true.
        // This test proves that strict mapper actually rejects an unknown field, so a typo'd field cannot be
        // dropped and silently change what the gates measure.
        ObjectMapper strict = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        String withExtra = "{\"id\":\"x\",\"task\":\"intent\",\"partition\":\"standard\","
                + "\"expectedLabel\":\"coder\",\"riskCategory\":\"BENIGN\","
                + "\"baselineAmbiguity\":\"UNAMBIGUOUS\",\"ambiguityRationale\":\"r\","
                + "\"input\":{\"message\":\"m\"},\"noSuchField\":42}";
        assertThrows(JsonMappingException.class, () -> strict.readValue(withExtra, DecisionEvidenceCase.class));
    }

    @Test
    void aCaseToStringIsPayloadFree() {
        DecisionEvidenceCase evidenceCase = new DecisionEvidenceCase(
                "intent-std-01",
                DecisionEvidenceCase.TASK_INTENT,
                "standard",
                "coder",
                List.of(),
                "BENIGN",
                DecisionEvidenceCase.BASELINE_UNAMBIGUOUS,
                "baseline selects one role",
                null,
                List.of("coder", "researcher"),
                Map.of("message", "user-secret-marker"),
                null);

        assertFalse(evidenceCase.toString().contains("user-secret-marker"), "toString must not carry state");
        assertTrue(evidenceCase.toString().contains("intent-std-01"), "toString must still identify the case");
    }

    @Test
    void refusesAnIntentCaseWithNoBaselineClassification() {
        // M2's denominator is the AMBIGUOUS subset, so an unclassified intent case would silently resize
        // the gate's denominator. The corpus must fail closed instead. An absent field raises NPE and a
        // blank one IAE, matching these records' established required-field contract.
        assertThrows(NullPointerException.class, () -> intentCase("x", null, "reason"));
        assertThrows(IllegalArgumentException.class, () -> intentCase("x", "", "reason"));
    }

    @Test
    void refusesAnIntentCaseWithNoAmbiguityRationale() {
        assertThrows(NullPointerException.class, () -> intentCase("x", DecisionEvidenceCase.BASELINE_AMBIGUOUS, null));
        assertThrows(
                IllegalArgumentException.class, () -> intentCase("x", DecisionEvidenceCase.BASELINE_UNAMBIGUOUS, "  "));
    }

    @Test
    void refusesAnUnknownBaselineClassification() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> intentCase("x", "PROBABLY_FINE", "reason"));
        assertTrue(thrown.getMessage().contains("baselineAmbiguity"), thrown.getMessage());
    }

    @Test
    void refusesABaselineClassificationOnANonIntentCase() {
        // Routing is an intent concern; a tool-risk case carrying the field would be misread as M2-eligible.
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvidenceCase(
                        "x",
                        DecisionEvidenceCase.TASK_TOOL_RISK,
                        "standard",
                        "LOW_RISK",
                        List.of(),
                        "SAFE",
                        DecisionEvidenceCase.BASELINE_AMBIGUOUS,
                        "reason",
                        null,
                        List.of(),
                        Map.of(),
                        null));
    }

    @Test
    void refusesACorpusWithNoBaselineOrAnUnusableOne() {
        // An absent baseline declaration is an NPE (required object field); an unusable one is an IAE.
        assertThrows(
                NullPointerException.class,
                () -> new DecisionEvidenceCorpus(
                        1,
                        "description",
                        "provenance",
                        null,
                        List.of(DecisionEvidenceCase.TASK_INTENT),
                        approved(),
                        Map.of(),
                        List.of(validCase("intent-std-01"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvidenceCorpus(
                        1,
                        "description",
                        "provenance",
                        new DecisionEvidenceCorpus.Baseline("  ", 1, "described"),
                        List.of(DecisionEvidenceCase.TASK_INTENT),
                        approved(),
                        Map.of(),
                        List.of(validCase("intent-std-01"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvidenceCorpus(
                        1,
                        "description",
                        "provenance",
                        new DecisionEvidenceCorpus.Baseline("amsha.deterministic.intent-baseline", 0, "described"),
                        List.of(DecisionEvidenceCase.TASK_INTENT),
                        approved(),
                        Map.of(),
                        List.of(validCase("intent-std-01"))));
    }

    @Test
    void refusesACorpusWithNoDeclaredEvaluatedScope() {
        // The scope of an evidence run is a reviewed property of the corpus, not an assumption in the runner:
        // without it the runner cannot tell a declared exclusion from an oversight.
        assertThrows(
                NullPointerException.class,
                () -> new DecisionEvidenceCorpus(
                        1,
                        "description",
                        "provenance",
                        baseline(),
                        null,
                        approved(),
                        Map.of(),
                        List.of(validCase("x"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvidenceCorpus(
                        1,
                        "description",
                        "provenance",
                        baseline(),
                        List.of(),
                        approved(),
                        Map.of(),
                        List.of(validCase("x"))));
    }

    @Test
    void refusesAnUnknownOrDuplicateEvaluatedTask() {
        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvidenceCorpus(
                        1,
                        "description",
                        "provenance",
                        baseline(),
                        List.of("mystery"),
                        approved(),
                        Map.of(),
                        List.of(validCase("x"))));
        assertTrue(unknown.getMessage().contains("unknown evaluated task"), unknown.getMessage());

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvidenceCorpus(
                        1,
                        "description",
                        "provenance",
                        baseline(),
                        List.of(DecisionEvidenceCase.TASK_INTENT, DecisionEvidenceCase.TASK_INTENT),
                        approved(),
                        Map.of(),
                        List.of(validCase("x"))));
        assertTrue(duplicate.getMessage().contains("duplicate evaluated task"), duplicate.getMessage());
    }

    @Test
    void refusesADeclaredEvaluatedTaskWithNoCases() {
        // A declared task with no case is a metric over an empty set: the runner would report it as complete
        // (0/0) and it would read as passing. A scope you cannot measure is not one you may declare.
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvidenceCorpus(
                        1,
                        "description",
                        "provenance",
                        baseline(),
                        List.of(DecisionEvidenceCase.TASK_INTENT, DecisionEvidenceCase.TASK_TOOL_RISK),
                        approved(),
                        Map.of(),
                        List.of(validCase("x"))));
        assertTrue(thrown.getMessage().contains("carries no case for it"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(DecisionEvidenceCase.TASK_TOOL_RISK), thrown.getMessage());
    }

    @Test
    void refusesAnIntentCaseWithNoDeclaredOptions() {
        // The runner no longer substitutes a default shortlist, so a case without one is unanswerable. The
        // corpus must fail closed at load rather than let the runner judge the case against an implicit list.
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvidenceCase(
                        "x",
                        DecisionEvidenceCase.TASK_INTENT,
                        "standard",
                        "coder",
                        List.of(),
                        "BENIGN",
                        DecisionEvidenceCase.BASELINE_UNAMBIGUOUS,
                        "reason",
                        null,
                        List.of(),
                        Map.of("message", "redacted"),
                        null));
        assertTrue(thrown.getMessage().contains("declares no options"), thrown.getMessage());
    }

    @Test
    void refusesAnIntentCaseWhoseExpectedLabelIsNotOffered() {
        // A label the case does not offer can never be produced, so the case would be unanswerable and the
        // gate would measure the harness rather than the model. Acceptable alternatives are checked too.
        IllegalArgumentException expectedNotOffered = assertThrows(
                IllegalArgumentException.class,
                () -> intentCaseWith("x", "coder", List.of(), List.of("researcher", "architect")));
        assertTrue(expectedNotOffered.getMessage().contains("expects label 'coder'"), expectedNotOffered.getMessage());

        IllegalArgumentException acceptableNotOffered = assertThrows(
                IllegalArgumentException.class,
                () -> intentCaseWith("x", "coder", List.of("debugger"), List.of("coder", "researcher")));
        assertTrue(
                acceptableNotOffered.getMessage().contains("expects label 'debugger'"),
                acceptableNotOffered.getMessage());
    }

    @Test
    void acceptsAnIntentCaseThatAbstainsWithoutOfferingNoOpinion() {
        // NO_OPINION is always an available outcome, so abstention cases need not list it among their options.
        DecisionEvidenceCase abstaining = intentCaseWith("x", "NO_OPINION", List.of(), List.of("coder", "researcher"));

        assertEquals("NO_OPINION", abstaining.expectedLabel());
        assertTrue(abstaining.isAbstentionExpected());
    }

    @Test
    void refusesOptionsOnANonIntentCase() {
        // Options are the intent shortlist; a non-intent case carrying them would be a field the runner
        // silently ignores — the same class of divergence the shortlist requirement exists to prevent.
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionEvidenceCase(
                        "x",
                        DecisionEvidenceCase.TASK_TOOL_RISK,
                        "standard",
                        "LOW_RISK",
                        List.of(),
                        "SAFE",
                        null,
                        null,
                        null,
                        List.of("coder"),
                        Map.of(),
                        null));
        assertTrue(thrown.getMessage().contains("only an intent case may declare options"), thrown.getMessage());
    }

    private static DecisionEvidenceCase intentCaseWith(
            String id, String expectedLabel, List<String> acceptable, List<String> options) {
        return new DecisionEvidenceCase(
                id,
                DecisionEvidenceCase.TASK_INTENT,
                "standard",
                expectedLabel,
                acceptable,
                "BENIGN",
                DecisionEvidenceCase.BASELINE_UNAMBIGUOUS,
                "baseline selects one role",
                null,
                options,
                Map.of("message", "redacted"),
                null);
    }

    private static DecisionEvidenceCorpus.RedactionReview approved() {
        return new DecisionEvidenceCorpus.RedactionReview("APPROVED", "2026-10-07T00:00:00Z", "verifier", false, false);
    }

    private static DecisionEvidenceCorpus.Baseline baseline() {
        return new DecisionEvidenceCorpus.Baseline(
                "amsha.deterministic.intent-baseline", 1, "the deterministic keyword baseline the plane replaces");
    }

    private static DecisionEvidenceCase validCase(String id) {
        return intentCase(id, DecisionEvidenceCase.BASELINE_UNAMBIGUOUS, "baseline selects one role");
    }

    private static DecisionEvidenceCase intentCase(String id, String ambiguity, String rationale) {
        return new DecisionEvidenceCase(
                id,
                DecisionEvidenceCase.TASK_INTENT,
                "standard",
                "coder",
                List.of(),
                "BENIGN",
                ambiguity,
                rationale,
                null,
                List.of("coder", "researcher"),
                Map.of("message", "redacted"),
                null);
    }

    private static DecisionEvidenceCorpus corpusWith(
            DecisionEvidenceCorpus.RedactionReview review, List<DecisionEvidenceCase> cases) {
        return new DecisionEvidenceCorpus(
                1,
                "description",
                "provenance",
                baseline(),
                List.of(DecisionEvidenceCase.TASK_INTENT),
                review,
                Map.of(),
                cases);
    }
}
