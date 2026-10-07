package com.amsha.core.classifier.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.classifier.api.DecisionMetadata;
import com.amsha.classifier.api.DecisionQuestion;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.InMemoryDecisionEngine;
import com.amsha.classifier.api.ProviderCategory;
import com.amsha.classifier.api.QuestionKey;
import com.amsha.classifier.api.RemoteDecisionProvider;
import com.amsha.classifier.api.ToolRiskPrediction;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.amsha.core.classifier.DecisionIntentShadowClassifier;
import com.amsha.core.classifier.DecisionToolRiskShadowClassifier;
import com.amsha.core.classifier.shadow.DecisionShadowComparisonHarness.CandidateType;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test running the shadow comparison harness against the redacted corpus,
 * verifying real CDI provider wiring and placement validation, and generating bounded evidence.
 */
class DecisionShadowEvaluationIT {

    private DecisionShadowComparisonHarness harness;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/decision-evidence/corpus-v1.json")) {
            assertNotNull(stream, "decision-evidence/corpus-v1.json must be present on classpath");
            harness = new DecisionShadowComparisonHarness(stream);
        }
    }

    @Test
    void executesShadowComparisonAndGeneratesReport() throws Exception {
        assertFalse(harness.corpus().isEmpty(), "Corpus must contain test cases");
        assertEquals(30, harness.corpus().size(), "Expected 30 redacted corpus cases across 9 partitions");

        Map<CandidateType, CandidateMetrics> results = harness.runComparison();
        assertEquals(6, results.size(), "Expected 6 evaluated candidates");

        // Verify Deterministic Rule Baseline (Measured pipeline verification)
        CandidateMetrics inProc = results.get(CandidateType.IN_PROCESS_DECISION);
        assertNotNull(inProc);
        assertEquals(0, inProc.falseAllows(), "Deterministic rule baseline must satisfy strict 0.0% false-allow gate");
        assertTrue(inProc.accuracyPct() >= 80.0, "Rule baseline accuracy must be valid across the corpus");
        assertTrue(inProc.p95LatencyMs() < 30.0, "Rule baseline p95 latency must be under 30ms");
        assertTrue(inProc.abstentions() > 0, "Rule baseline must abstain on ambiguous/contradictory cases");

        // Verify Keyword Baseline Vulnerability (Measured)
        CandidateMetrics keyword = results.get(CandidateType.KEYWORD);
        assertNotNull(keyword);
        assertTrue(keyword.falseAllows() > 0, "Keyword heuristic baseline exhibits false allow on hostile injection");

        // Verify that simulation candidates are explicitly marked
        assertTrue(CandidateType.STRUCTURED_LLM.isSimulation());
        assertTrue(CandidateType.ONNX.isSimulation());
        assertTrue(CandidateType.SELF_HOSTED.isSimulation());
        assertTrue(CandidateType.REMOTE.isSimulation());
        assertFalse(CandidateType.IN_PROCESS_DECISION.isSimulation());
        assertFalse(CandidateType.KEYWORD.isSimulation());

        // Generate evidence reports
        String markdownReport = harness.generateMarkdownReport(results);
        assertNotNull(markdownReport);
        assertTrue(markdownReport.contains("Deterministic Rule Baseline"));
        assertTrue(markdownReport.contains("Illustrative Simulation"));
        assertTrue(markdownReport.contains("N/A (Synthetic)"));
        assertTrue(markdownReport.contains("Calibration Gap Proxy"));
        assertTrue(markdownReport.contains("IN_PROCESS_MEMORY_ONLY"));
        assertTrue(markdownReport.contains("EPHEMERAL_DISCARD_ON_TURN_FINISH"));
        assertTrue(markdownReport.contains("No Vendor Benchmarks"));
        String remoteRow = markdownReport
                .lines()
                .filter(line -> line.contains("Remote Provider"))
                .findFirst()
                .orElseThrow();
        assertEquals(8, remoteRow.split("N/A \\(Synthetic\\)", -1).length - 1);

        Path evidenceDir = resolveEvidenceDir();
        Files.createDirectories(evidenceDir);

        Path mdFile = evidenceDir.resolve("shadow_evaluation_report.md");
        Files.writeString(mdFile, markdownReport, StandardCharsets.UTF_8);
        assertTrue(Files.exists(mdFile));

        Path jsonFile = evidenceDir.resolve("shadow_evaluation_report.json");
        Map<String, Object> jsonResults = new LinkedHashMap<>();
        results.forEach((candidate, metrics) -> {
            Map<String, Object> candidateReport = new LinkedHashMap<>();
            candidateReport.put("candidateName", metrics.candidateName());
            candidateReport.put("kind", candidate.isSimulation() ? "Illustrative Simulation" : "Measured");
            candidateReport.put("totalCases", metrics.totalCases());
            candidateReport.put("metrics", candidate.isSimulation() ? "N/A (Synthetic)" : metrics);
            jsonResults.put(candidate.name(), candidateReport);
        });
        mapper.writeValue(jsonFile.toFile(), jsonResults);
        assertTrue(Files.exists(jsonFile));
        var jsonReport = mapper.readTree(jsonFile.toFile());
        assertEquals(
                "N/A (Synthetic)", jsonReport.path("REMOTE").path("metrics").asText());
        assertTrue(jsonReport.path("IN_PROCESS_DECISION").path("metrics").has("falseAllows"));
    }

    @Test
    void verifiesDuplicateIdentifierRejection() {
        // 1. Duplicate question key in DecisionRequest fails closed
        var reqBuilder = DecisionRequest.builder("test.dup", 1, "test state");
        reqBuilder.registerChoice("q1", "first question", List.of("opt1", "opt2"));
        assertThrows(
                IllegalArgumentException.class,
                () -> reqBuilder.registerChoice("q1", "duplicate handle", List.of("opt3", "opt4")));

        // 2. Duplicate choice options in ChoiceQuestion fails closed
        assertThrows(
                IllegalArgumentException.class, () -> DecisionQuestion.choice("q2", "prompt", List.of("optA", "optA")));

        // 3. Duplicate decision ID in InMemoryDecisionEngine fails closed
        var result1 = new DecisionResult.NoOpinion("dec.1", 1, ClassifierResult.NoOpinionReason.DISABLED);
        var result2 = new DecisionResult.NoOpinion("dec.1", 1, ClassifierResult.NoOpinionReason.TIMEOUT);
        assertThrows(
                IllegalArgumentException.class,
                () -> InMemoryDecisionEngine.builder()
                        .withResult("dec.1", result1)
                        .withResult("dec.1", result2));

        // 4. Duplicate identifier test case in corpus is evaluated
        ShadowTestCase dupCase = harness.corpus().stream()
                .filter(c -> c.partition() == ShadowCorpusPartition.DUPLICATE_IDENTIFIERS)
                .findFirst()
                .orElse(null);
        assertNotNull(dupCase, "Corpus must include duplicate_identifiers partition case");
        assertEquals("toolrisk-dup-01", dupCase.id());

        // 5. Duplicate test case IDs in corpus JSON fail closed in ShadowCorpusLoader
        String duplicateCaseJson = """
                {
                  "cases": [
                    {"id": "dup-1", "task": "intent", "partition": "standard", "expectedLabel": "coder"},
                    {"id": "dup-1", "task": "intent", "partition": "standard", "expectedLabel": "coder"}
                  ]
                }
                """;
        var byteStream = new ByteArrayInputStream(duplicateCaseJson.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> ShadowCorpusLoader.load(byteStream));

        // 6. Strict duplicate JSON field names (e.g. duplicate parameter keys) fail closed with IOException
        String duplicateFieldJson = """
                {
                  "cases": [
                    {
                      "id": "dup-field-1",
                      "task": "tool_risk",
                      "partition": "standard",
                      "expectedLabel": "LOW_RISK",
                      "input": {
                        "parameters": {
                          "path": "first.txt",
                          "path": "duplicate.txt"
                        }
                      }
                    }
                  ]
                }
                """;
        var dupFieldStream = new ByteArrayInputStream(duplicateFieldJson.getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> ShadowCorpusLoader.load(dupFieldStream));
    }

    @Test
    void rejectsCorpusCasesMissingRequiredEvaluationFields() {
        List<String> incompleteCases = List.of("""
                {"cases":[{"id":"missing-task","partition":"standard","expectedLabel":"coder"}]}
                """, """
                {"cases":[{"id":"missing-partition","task":"intent","expectedLabel":"coder"}]}
                """, """
                {"cases":[{"id":"missing-label","task":"intent","partition":"standard"}]}
                """);

        for (String json : incompleteCases) {
            var stream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> ShadowCorpusLoader.load(stream));
        }
    }

    @Test
    void verifiesProviderPlacementValidation() {
        // 1. Valid in-process engine accepted
        var validEngine = InMemoryDecisionEngine.builder()
                .withAnswer(QuestionKey.choice("role"), new Choice("coder", OptionalDouble.of(0.92)))
                .withRuleId("rule-intent")
                .build();

        DecisionIntentShadowClassifier intentClassifier = new DecisionIntentShadowClassifier(validEngine, true);
        ClassifierResult<String> intentResult = intentClassifier.classify("Implement a feature in Java");
        assertInstanceOf(ClassifierResult.Opinion.class, intentResult);
        var op = (ClassifierResult.Opinion<String>) intentResult;
        assertEquals("coder", op.value());
        assertEquals(0.92f, op.confidence(), 1e-4f);
        assertEquals("rule-intent", op.ruleId());

        // 2. Real Tool Risk Shadow Classifier with valid in-process engine
        var toolRiskEngine = InMemoryDecisionEngine.builder()
                .withAnswer(
                        QuestionKey.choice(ShadowCorpusTask.TOOL_RISK.taskName()),
                        new Choice("BLOCK", OptionalDouble.of(0.98)))
                .withRuleId("rule-block")
                .build();

        DecisionToolRiskShadowClassifier toolRiskClassifier =
                new DecisionToolRiskShadowClassifier(toolRiskEngine, mapper, true);
        ToolRiskShadowContext ctx = new ToolRiskShadowContext(
                "coder", AutonomyLevel.FULL, "execute_shell", Map.of("cmd", "rm -rf /"), "delete root");
        ClassifierResult<ToolRiskPrediction> riskResult = toolRiskClassifier.classify(ctx);
        assertInstanceOf(ClassifierResult.Opinion.class, riskResult);
        assertEquals(ToolRiskPrediction.BLOCK, ((ClassifierResult.Opinion<ToolRiskPrediction>) riskResult).value());

        // 3. Security Placement Boundary: Remote engine rejected with SecurityException
        class MaliciousRemoteEngine implements DecisionEngine, RemoteDecisionProvider {
            @Override
            public DecisionResult evaluate(DecisionRequest request) {
                return new DecisionResult.Opinion(
                        request.decisionId(),
                        request.schemaVersion(),
                        Map.of(QuestionKey.choice("role"), new Choice("coder", OptionalDouble.of(1.0))),
                        "malicious-rule",
                        DecisionMetadata.empty());
            }

            @Override
            public ProviderCategory category() {
                return ProviderCategory.REMOTE;
            }
        }

        assertThrows(
                SecurityException.class, () -> new DecisionIntentShadowClassifier(new MaliciousRemoteEngine(), true));
    }

    @Test
    void verifiesTimeoutAndProviderFailureHandling() {
        // 1. InMemoryDecisionEngine simulated timeout -> NoOpinion(TIMEOUT)
        var timeoutEngine = InMemoryDecisionEngine.ofSimulatedTimeout();
        DecisionIntentShadowClassifier timeoutClassifier = new DecisionIntentShadowClassifier(timeoutEngine, true);
        ClassifierResult<String> timeoutRes = timeoutClassifier.classify("Implement a feature");
        assertInstanceOf(ClassifierResult.NoOpinion.class, timeoutRes);
        assertEquals(
                ClassifierResult.NoOpinionReason.TIMEOUT, ((ClassifierResult.NoOpinion<String>) timeoutRes).reason());

        // 2. InMemoryDecisionEngine simulated failure (OVERLOAD_REJECTED) -> NoOpinion(OVERLOAD_REJECTED)
        var failureEngine = InMemoryDecisionEngine.ofNoOpinion(ClassifierResult.NoOpinionReason.OVERLOAD_REJECTED);
        DecisionIntentShadowClassifier failureClassifier = new DecisionIntentShadowClassifier(failureEngine, true);
        ClassifierResult<String> failureRes = failureClassifier.classify("Implement a feature");
        assertInstanceOf(ClassifierResult.NoOpinion.class, failureRes);
        assertEquals(
                ClassifierResult.NoOpinionReason.OVERLOAD_REJECTED,
                ((ClassifierResult.NoOpinion<String>) failureRes).reason());

        // 3. Simulated inference error failure -> fails closed to NoOpinion(INFERENCE_ERROR)
        var throwingEngine = InMemoryDecisionEngine.builder()
                .withSimulatedFailure(ClassifierResult.NoOpinionReason.INFERENCE_ERROR, "simulated explosion")
                .build();
        DecisionIntentShadowClassifier throwingClassifier = new DecisionIntentShadowClassifier(throwingEngine, true);
        ClassifierResult<String> throwingRes = throwingClassifier.classify("Implement a feature");
        assertInstanceOf(ClassifierResult.NoOpinion.class, throwingRes);
        assertEquals(
                ClassifierResult.NoOpinionReason.INFERENCE_ERROR,
                ((ClassifierResult.NoOpinion<String>) throwingRes).reason());

        // 4. Assert harness candidate metrics record timeouts for remote candidates
        Map<CandidateType, CandidateMetrics> results = harness.runComparison();
        CandidateMetrics remote = results.get(CandidateType.REMOTE);
        assertNotNull(remote);
        assertTrue(remote.timeouts() > 0, "Remote candidate simulation must record timeouts on contradictory cases");
    }

    private static Path resolveEvidenceDir() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve(".scratch"))) {
            current = current.getParent();
        }
        if (current != null && Files.exists(current.resolve(".scratch"))) {
            return current.resolve(".scratch/jev-decision-plane/evidence");
        }
        return Path.of("../.scratch/jev-decision-plane/evidence");
    }
}
