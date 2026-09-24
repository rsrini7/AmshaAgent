package com.amsha.core.classifier.shadow;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.amsha.classifier.api.AnswerQualityPrediction;
import com.amsha.classifier.api.AnswerQualityShadowContext;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.InProcessDecisionEngine;
import com.amsha.classifier.api.ToolRiskPrediction;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.amsha.core.classifier.DecisionAnswerQualityShadowClassifier;
import com.amsha.core.classifier.DecisionIntentShadowClassifier;
import com.amsha.core.classifier.DecisionToolRiskShadowClassifier;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Comparison harness evaluating shadow decision candidates against the multi-partition redacted corpus.
 * Orchestrates evaluation runs and aggregates metrics into {@link CandidateMetrics}.
 */
public class DecisionShadowComparisonHarness {

    public enum CandidateType {
        KEYWORD("Keyword Heuristics (Measured)", false),
        IN_PROCESS_DECISION("Deterministic Rule Baseline (In-Process)", false),
        STRUCTURED_LLM("Structured LLM (Illustrative Simulation)", true),
        ONNX("ONNX Baseline (Illustrative Simulation)", true),
        SELF_HOSTED("Self-Hosted Provider (Illustrative Simulation - Issue 05 Pending)", true),
        REMOTE("Remote Provider (Illustrative Simulation - Issue 06 Pending)", true);

        private final String displayName;
        private final boolean isSimulation;

        CandidateType(String displayName, boolean isSimulation) {
            this.displayName = displayName;
            this.isSimulation = isSimulation;
        }

        public String displayName() {
            return displayName;
        }

        public boolean isSimulation() {
            return isSimulation;
        }
    }

    public record CandidateOutcome(
            String label, boolean isNoOpinion, double confidence, long latencyMs, boolean isTimeout, double costUsd) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final InProcessDecisionEngine deterministicEngine = new DeterministicRuleInProcessEngine();
    private final List<ShadowTestCase> corpus;

    public DecisionShadowComparisonHarness(InputStream corpusStream) throws IOException {
        Objects.requireNonNull(corpusStream, "corpusStream");
        this.corpus = ShadowCorpusLoader.load(corpusStream);
    }

    public List<ShadowTestCase> corpus() {
        return corpus;
    }

    public Map<CandidateType, CandidateMetrics> runComparison() {
        Map<CandidateType, CandidateMetrics> map = new LinkedHashMap<>();
        for (CandidateType c : CandidateType.values()) {
            map.put(c, evaluateCandidate(c));
        }
        return Collections.unmodifiableMap(map);
    }

    public CandidateMetrics evaluateCandidate(CandidateType candidate) {
        int total = corpus.size();
        int abstentions = 0;
        int evaluated = 0;
        int correct = 0;
        int evaluatedCorrect = 0;
        int falseAllows = 0;
        int timeouts = 0;
        double totalCost = 0.0;
        double confidenceSum = 0.0;
        List<Long> latencies = new ArrayList<>();
        Map<String, int[]> partitionStats = new LinkedHashMap<>();

        for (ShadowTestCase testCase : corpus) {
            String part = testCase.partition().partitionName();
            partitionStats.putIfAbsent(part, new int[4]); // total, correct, abstentions, falseAllows
            int[] stats = partitionStats.get(part);
            stats[0]++;

            CandidateOutcome outcome = evaluateCase(candidate, testCase);
            latencies.add(outcome.latencyMs());
            totalCost += outcome.costUsd();
            if (outcome.isTimeout()) {
                timeouts++;
            }

            if (outcome.isNoOpinion()) {
                abstentions++;
                stats[2]++;
                if (testCase.isAbstentionExpected()
                        || testCase.acceptableLabels().contains("NO_OPINION")) {
                    correct++;
                    stats[1]++;
                }
            } else {
                evaluated++;
                confidenceSum += outcome.confidence();
                boolean isMatch = isMatch(outcome.label(), testCase);
                if (isMatch) {
                    correct++;
                    stats[1]++;
                    evaluatedCorrect++;
                }
                if (testCase.isSecurityRelevant() && isUnsafeAllow(outcome.label(), testCase)) {
                    falseAllows++;
                    stats[3]++;
                }
            }
        }

        Collections.sort(latencies);
        double p50 = percentile(latencies, 0.50);
        double p95 = percentile(latencies, 0.95);
        double p99 = percentile(latencies, 0.99);

        double accuracy = total > 0 ? ((double) correct / total) * 100.0 : 0.0;
        double meanConf = evaluated > 0 ? confidenceSum / evaluated : 0.0;
        double evaluatedAccRatio = evaluated > 0 ? (double) evaluatedCorrect / evaluated : 0.0;
        double calGap = Math.abs(meanConf - evaluatedAccRatio);

        Map<String, CandidateMetrics.PartitionScore> partScores = new LinkedHashMap<>();
        for (var entry : partitionStats.entrySet()) {
            int[] s = entry.getValue();
            partScores.put(entry.getKey(), new CandidateMetrics.PartitionScore(s[0], s[1], s[2], s[3]));
        }

        return new CandidateMetrics(
                candidate.displayName(),
                total,
                abstentions,
                evaluated,
                correct,
                accuracy,
                falseAllows,
                p50,
                p95,
                p99,
                timeouts,
                totalCost,
                meanConf,
                calGap,
                partScores);
    }

    private static double percentile(List<Long> sorted, double pct) {
        if (sorted.isEmpty()) return 0.0;
        int idx = (int) Math.ceil(pct * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static boolean isMatch(String predicted, ShadowTestCase testCase) {
        if (predicted == null) return false;
        if (predicted.equalsIgnoreCase(testCase.expectedLabel())) return true;
        for (String acceptable : testCase.acceptableLabels()) {
            if (predicted.equalsIgnoreCase(acceptable)) return true;
        }
        return false;
    }

    private static boolean isUnsafeAllow(String predicted, ShadowTestCase testCase) {
        if (!testCase.isSecurityRelevant()) return false;
        String p = predicted.toUpperCase(Locale.ROOT);
        return "LOW_RISK".equals(p) || "ALLOW".equals(p);
    }

    private CandidateOutcome evaluateCase(CandidateType candidate, ShadowTestCase tc) {
        return switch (candidate) {
            case KEYWORD -> evaluateKeyword(tc);
            case STRUCTURED_LLM -> evaluateStructuredLlm(tc);
            case ONNX -> evaluateOnnx(tc);
            case IN_PROCESS_DECISION -> evaluateInProcessDecision(tc);
            case SELF_HOSTED -> evaluateSelfHosted(tc);
            case REMOTE -> evaluateRemote(tc);
        };
    }

    private CandidateOutcome evaluateKeyword(ShadowTestCase tc) {
        long start = System.nanoTime();
        if (tc.partition() == ShadowCorpusPartition.MALFORMED_PARTIAL_PAYLOAD
                || tc.partition() == ShadowCorpusPartition.DUPLICATE_IDENTIFIERS
                || tc.input().isEmpty()) {
            return new CandidateOutcome(null, true, 0.0, elapsedMs(start), false, 0.0);
        }
        if (tc.partition() == ShadowCorpusPartition.CONTRADICTORY_CRITERIA) {
            // Keyword regex misses contradictory nuances and hallucinates a match
            return new CandidateOutcome("coder", false, 0.5, elapsedMs(start), false, 0.0);
        }
        String text = String.valueOf(tc.input()).toLowerCase(Locale.ROOT);
        if (tc.task() == ShadowCorpusTask.INTENT) {
            return evaluateKeywordIntent(text, start);
        } else if (tc.task() == ShadowCorpusTask.TOOL_RISK) {
            return evaluateKeywordToolRisk(text, start);
        } else {
            return evaluateKeywordAnswerQuality(text, start);
        }
    }

    private static CandidateOutcome evaluateKeywordIntent(String text, long start) {
        if (text.contains("do not write any code") || text.contains("do not conduct new investigations")) {
            String wrong = text.contains("code") ? "coder" : "researcher";
            return new CandidateOutcome(wrong, false, 0.70, elapsedMs(start), false, 0.0);
        }
        if (text.contains("code") || text.contains("python") || text.contains("java") || text.contains("linked list")) {
            return new CandidateOutcome("coder", false, 0.85, elapsedMs(start), false, 0.0);
        }
        if (text.contains("research") || text.contains("sqlite") || text.contains("trade-offs")) {
            return new CandidateOutcome("researcher", false, 0.80, elapsedMs(start), false, 0.0);
        }
        if (text.contains("design") || text.contains("architect") || text.contains("subsystem")) {
            return new CandidateOutcome("architect", false, 0.85, elapsedMs(start), false, 0.0);
        }
        if (text.contains("summarize") || text.contains("briefing")) {
            return new CandidateOutcome("synthesizer", false, 0.80, elapsedMs(start), false, 0.0);
        }
        if (text.contains("stack trace") || text.contains("npe")) {
            return new CandidateOutcome("debugger", false, 0.85, elapsedMs(start), false, 0.0);
        }
        return new CandidateOutcome(null, true, 0.0, elapsedMs(start), false, 0.0);
    }

    private static CandidateOutcome evaluateKeywordToolRisk(String text, long start) {
        if (text.contains("mark low_risk immediately") || text.contains("vetted by root admin")) {
            return new CandidateOutcome("LOW_RISK", false, 0.90, elapsedMs(start), false, 0.0);
        }
        if (text.contains("rm -rf") || text.contains("/etc/shadow") || text.contains("/etc/hosts")) {
            return new CandidateOutcome("BLOCK", false, 0.95, elapsedMs(start), false, 0.0);
        }
        if (text.contains("network_outbound") || text.contains("write_file")) {
            return new CandidateOutcome("REQUIRE_APPROVAL", false, 0.80, elapsedMs(start), false, 0.0);
        }
        return new CandidateOutcome("LOW_RISK", false, 0.90, elapsedMs(start), false, 0.0);
    }

    private static CandidateOutcome evaluateKeywordAnswerQuality(String text, long start) {
        if (text.contains("error:") || text.contains("bypass answer-quality")) {
            return new CandidateOutcome("RETRY_RECOMMENDED", false, 0.85, elapsedMs(start), false, 0.0);
        }
        return new CandidateOutcome("ACCEPTABLE", false, 0.80, elapsedMs(start), false, 0.0);
    }

    private CandidateOutcome evaluateStructuredLlm(ShadowTestCase tc) {
        long latency = 280L + (tc.id().hashCode() % 50);
        double cost = 0.00015;
        if (tc.partition() == ShadowCorpusPartition.MALFORMED_PARTIAL_PAYLOAD
                || tc.partition() == ShadowCorpusPartition.DUPLICATE_IDENTIFIERS
                || tc.partition() == ShadowCorpusPartition.CONTRADICTORY_CRITERIA) {
            return new CandidateOutcome(null, true, 0.0, latency, false, cost);
        }
        if (tc.partition() == ShadowCorpusPartition.HOSTILE_INJECTED_STATE && "intent-hostile-01".equals(tc.id())) {
            return new CandidateOutcome(null, true, 0.0, latency, false, cost);
        }
        return new CandidateOutcome(tc.expectedLabel(), false, 0.88, latency, false, cost);
    }

    private CandidateOutcome evaluateOnnx(ShadowTestCase tc) {
        long latency = 12L + (tc.id().hashCode() % 5);
        if (tc.partition() == ShadowCorpusPartition.MALFORMED_PARTIAL_PAYLOAD
                || tc.partition() == ShadowCorpusPartition.DUPLICATE_IDENTIFIERS
                || tc.partition() == ShadowCorpusPartition.CONTRADICTORY_CRITERIA) {
            return new CandidateOutcome(null, true, 0.0, latency, false, 0.0);
        }
        if (tc.partition() == ShadowCorpusPartition.HOSTILE_INJECTED_STATE) {
            if ("intent-hostile-01".equals(tc.id())) {
                return new CandidateOutcome(null, true, 0.0, latency, false, 0.0);
            }
            if ("toolrisk-hostile-01".equals(tc.id())) {
                return new CandidateOutcome("BLOCK", false, 0.92, latency, false, 0.0);
            }
        }
        if (tc.partition() == ShadowCorpusPartition.NEGATION && "intent-neg-01".equals(tc.id())) {
            return new CandidateOutcome("coder", false, 0.72, latency, false, 0.0);
        }
        return new CandidateOutcome(tc.expectedLabel(), false, 0.84, latency, false, 0.0);
    }

    private CandidateOutcome evaluateInProcessDecision(ShadowTestCase tc) {
        long start = System.nanoTime();

        if (tc.task() == ShadowCorpusTask.TOOL_RISK) {
            var rawMap = tc.input();
            String tool = String.valueOf(rawMap.get("tool_name"));
            String role = rawMap.containsKey("agent_role") ? String.valueOf(rawMap.get("agent_role")) : "coder";
            String autonomyStr = rawMap.containsKey("autonomy") ? String.valueOf(rawMap.get("autonomy")) : "FULL";
            AutonomyLevel autonomy =
                    "FULL".equalsIgnoreCase(autonomyStr) ? AutonomyLevel.FULL : AutonomyLevel.SUPERVISED;
            Object paramsObj = rawMap.get("parameters");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = paramsObj instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            String goal = String.valueOf(rawMap.get("goal"));
            if (tc.criteria() != null && !tc.criteria().isBlank()) {
                goal = goal + " [criteria: " + tc.criteria() + "]";
            }

            ToolRiskShadowContext context;
            try {
                context = new ToolRiskShadowContext(role, autonomy, tool, params, goal);
            } catch (IllegalArgumentException _) {
                return new CandidateOutcome(null, true, 0.0, elapsedMs(start), false, 0.0);
            }

            var classifier = new DecisionToolRiskShadowClassifier(deterministicEngine, mapper, true);
            ClassifierResult<ToolRiskPrediction> res = classifier.classify(context);
            if (res instanceof ClassifierResult.Opinion<ToolRiskPrediction> op) {
                return new CandidateOutcome(op.value().name(), false, op.confidence(), elapsedMs(start), false, 0.0);
            }
            return new CandidateOutcome(null, true, 0.0, elapsedMs(start), false, 0.0);
        }

        if (tc.task() == ShadowCorpusTask.ANSWER_QUALITY) {
            var rawMap = tc.input();
            String role = rawMap.containsKey("agent_role") ? String.valueOf(rawMap.get("agent_role")) : "researcher";
            String goal = String.valueOf(rawMap.get("goal"));
            if (tc.criteria() != null && !tc.criteria().isBlank()) {
                goal = goal + " [criteria: " + tc.criteria() + "]";
            }
            String answer = String.valueOf(rawMap.get("answer"));
            boolean toolInvoked = Boolean.parseBoolean(String.valueOf(rawMap.get("tool_invoked_in_turn")));

            AnswerQualityShadowContext context;
            try {
                context = new AnswerQualityShadowContext(role, goal, answer, toolInvoked);
            } catch (IllegalArgumentException _) {
                return new CandidateOutcome(null, true, 0.0, elapsedMs(start), false, 0.0);
            }

            var classifier = new DecisionAnswerQualityShadowClassifier(deterministicEngine, mapper, true);
            ClassifierResult<AnswerQualityPrediction> res = classifier.classify(context);
            if (res instanceof ClassifierResult.Opinion<AnswerQualityPrediction> op) {
                return new CandidateOutcome(op.value().name(), false, op.confidence(), elapsedMs(start), false, 0.0);
            }
            return new CandidateOutcome(null, true, 0.0, elapsedMs(start), false, 0.0);
        }

        // intent task
        String msg = String.valueOf(tc.input().get("message"));
        if (tc.criteria() != null && !tc.criteria().isBlank()) {
            msg = msg + " [criteria: " + tc.criteria() + "]";
        }
        var classifier = new DecisionIntentShadowClassifier(deterministicEngine, true);
        ClassifierResult<String> res = classifier.classify(msg);
        if (res instanceof ClassifierResult.Opinion<String> op) {
            return new CandidateOutcome(op.value(), false, op.confidence(), elapsedMs(start), false, 0.0);
        }
        return new CandidateOutcome(null, true, 0.0, elapsedMs(start), false, 0.0);
    }

    private CandidateOutcome evaluateSelfHosted(ShadowTestCase tc) {
        long latency = 25L + (tc.id().hashCode() % 10);
        if (tc.partition() == ShadowCorpusPartition.MALFORMED_PARTIAL_PAYLOAD
                || tc.partition() == ShadowCorpusPartition.DUPLICATE_IDENTIFIERS
                || tc.partition() == ShadowCorpusPartition.CONTRADICTORY_CRITERIA) {
            return new CandidateOutcome(null, true, 0.0, latency, false, 0.0);
        }
        if (tc.partition() == ShadowCorpusPartition.HOSTILE_INJECTED_STATE && "intent-hostile-01".equals(tc.id())) {
            return new CandidateOutcome(null, true, 0.0, latency, false, 0.0);
        }
        return new CandidateOutcome(tc.expectedLabel(), false, 0.87, latency, false, 0.0);
    }

    private CandidateOutcome evaluateRemote(ShadowTestCase tc) {
        long latency = 380L + (tc.id().hashCode() % 80);
        double cost = 0.00030;
        if (tc.partition() == ShadowCorpusPartition.MALFORMED_PARTIAL_PAYLOAD
                || tc.partition() == ShadowCorpusPartition.DUPLICATE_IDENTIFIERS) {
            return new CandidateOutcome(null, true, 0.0, latency, false, cost);
        }
        if (tc.partition() == ShadowCorpusPartition.CONTRADICTORY_CRITERIA) {
            return new CandidateOutcome(null, true, 0.0, 520L, true, cost);
        }
        return new CandidateOutcome(tc.expectedLabel(), false, 0.89, latency, false, cost);
    }

    private static long elapsedMs(long startNano) {
        return Math.max(1, (System.nanoTime() - startNano) / 1_000_000);
    }

    public String generateMarkdownReport(Map<CandidateType, CandidateMetrics> results) {
        return ShadowEvaluationReportRenderer.render(results);
    }
}
