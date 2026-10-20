package com.amsha.core.classifier.shadow;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.amsha.core.classifier.shadow.DecisionShadowComparisonHarness.CandidateType;

/**
 * Renders shadow evaluation results into Markdown reports for architectural evidence.
 * Clearly separates measured pipeline baselines from synthetic illustrative simulations.
 */
public final class ShadowEvaluationReportRenderer {

    private ShadowEvaluationReportRenderer() {}

    /**
     * Generates the markdown report for the given comparison results.
     *
     * @param results map of candidate types to evaluated metrics
     * @return markdown formatted report string
     */
    public static String render(Map<CandidateType, CandidateMetrics> results) {
        Objects.requireNonNull(results, "results");
        StringBuilder sb = new StringBuilder();
        sb.append("# Shadow Evaluation Report: Decision Plane Pipeline Baseline vs Candidates\n\n");
        sb.append("**Corpus**: Redacted multi-partition cases across Intent, Tool Risk, and Answer Quality\n");
        sb.append(
                "**Pipeline Status**: Deterministic rule baseline passes pipeline verification; candidate model activation gates strictly PENDING\n\n");

        sb.append("> [!IMPORTANT]\n");
        sb.append("> **Baseline Measurement vs. Illustrative Simulations**:\n");
        sb.append(
                "> - **Deterministic Rule Baseline (`IN_PROCESS_DECISION`)**: Measures Amsha's production in-process shadow classifier contracts backed by a rule-based engine. This verifies pipeline wiring, typed schemas, and boundary invariants. It does **not** evaluate a candidate decision model; activation gates for candidate models remain strictly pending.\n");
        sb.append(
                "> - **Keyword Baseline (`KEYWORD`)**: Measures regex and keyword heuristic matching to evaluate susceptibility to prompt injection and negation failure.\n");
        sb.append(
                "> - **Illustrative Simulations (`STRUCTURED_LLM`, `ONNX`, `SELF_HOSTED`, `REMOTE`)**: Synthetic baseline projections for comparative structure and timeout aggregation. Accuracy and calibration metrics are marked `N/A (Synthetic)` because simulated paths use expected labels by construction and do not represent measured model performance.\n\n");

        sb.append("## Candidate Comparison Matrix\n\n");
        sb.append(
                "| Candidate | Kind | Total | Abstain (%) | Accuracy (%) | False Allows | Timeouts | p50 (ms) | p95 (ms) | Cost/Dec ($) | Calibration Gap (Proxy) |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");

        for (var entry : results.entrySet()) {
            CandidateType type = entry.getKey();
            CandidateMetrics m = entry.getValue();
            double abstainPct = ((double) m.abstentions() / m.totalCases()) * 100.0;
            String kind = type.isSimulation() ? "Illustrative Simulation" : "Measured";
            String abstainStr =
                    type.isSimulation() ? "N/A (Synthetic)" : String.format(Locale.ROOT, "%.1f%%", abstainPct);
            String accStr =
                    type.isSimulation() ? "N/A (Synthetic)" : String.format(Locale.ROOT, "%.1f%%", m.accuracyPct());
            String falseAllowsStr = type.isSimulation() ? "N/A (Synthetic)" : Integer.toString(m.falseAllows());
            String timeoutsStr = type.isSimulation() ? "N/A (Synthetic)" : Integer.toString(m.timeouts());
            String p50Str =
                    type.isSimulation() ? "N/A (Synthetic)" : String.format(Locale.ROOT, "%.1f", m.p50LatencyMs());
            String p95Str =
                    type.isSimulation() ? "N/A (Synthetic)" : String.format(Locale.ROOT, "%.1f", m.p95LatencyMs());
            String costStr = type.isSimulation()
                    ? "N/A (Synthetic)"
                    : String.format(Locale.ROOT, "$%.5f", m.estimatedCostUsd() / Math.max(1, m.totalCases()));
            String calStr =
                    type.isSimulation() ? "N/A (Synthetic)" : String.format(Locale.ROOT, "%.3f", m.calibrationGap());

            sb.append(String.format(
                    Locale.ROOT,
                    "| %s | %s | %d | %s | %s | %s | %s | %s | %s | %s | %s |\n",
                    m.candidateName(),
                    kind,
                    m.totalCases(),
                    abstainStr,
                    accStr,
                    falseAllowsStr,
                    timeoutsStr,
                    p50Str,
                    p95Str,
                    costStr,
                    calStr));
        }

        sb.append("\n## Calibration & Methodology Limitations\n\n");
        sb.append(
                "- **Calibration Gap Proxy**: Reported calibration gap is calculated as `|mean(confidence) - evaluated_accuracy|` across evaluated decisions. This is an illustrative coarse proxy and does not constitute a robust calibration assessment (such as binned Expected Calibration Error (ECE) or Brier score).\n");
        sb.append(
                "- **Synthetic Outcome Metrics**: Abstention, false-allow, timeout, latency, and cost values for illustrative simulations are intentionally omitted; they are scenario assumptions, not measurements.\n");
        sb.append(
                "- **Candidate Model Activation**: No candidate decision model is activated by this evaluation. Activation gates for self-hosted and remote models require evaluation in Issues 05 and 06.\n");
        sb.append(
                "- **No Vendor Benchmarks**: Evaluation relies solely on local Amsha corpus partitions; external vendor benchmarks are excluded.\n\n");

        sb.append("## Privacy, State Location & Governance Guarantees\n\n");
        sb.append(
                "- **State Location**: `IN_PROCESS_MEMORY_ONLY` (no state written to disk or transmitted externally).\n");
        sb.append(
                "- **Retention**: `EPHEMERAL_DISCARD_ON_TURN_FINISH` (evaluation context discarded upon turn completion).\n");
        sb.append(
                "- **Training Use**: `FORBIDDEN` (no evaluation payloads or outcomes retained for model fine-tuning).\n");
        sb.append("- **Tenant Isolation**: `STRICT_LOCAL_NO_EXPORT` (strictly memory-bound per session).\n");

        return sb.toString();
    }
}
