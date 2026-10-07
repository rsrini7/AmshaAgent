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
    void anUnshippedVersionIsAbsentRatherThanInvented() {
        DecisionEvidenceCorpusRegistry registry = new DecisionEvidenceCorpusRegistry(MAPPER);

        assertTrue(registry.find(99).isEmpty(), "a version this build does not ship must be absent");
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
                List.of(),
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
                        approved(),
                        Map.of(),
                        List.of(validCase("intent-std-01"))));
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
                List.of(),
                Map.of("message", "redacted"),
                null);
    }

    private static DecisionEvidenceCorpus corpusWith(
            DecisionEvidenceCorpus.RedactionReview review, List<DecisionEvidenceCase> cases) {
        return new DecisionEvidenceCorpus(1, "description", "provenance", baseline(), review, Map.of(), cases);
    }
}
