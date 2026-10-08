package com.amsha.core.classifier.evaluation;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Renders a {@link DecisionEvidenceSummary} as a payload-free Markdown report.
 *
 * <p>The summary is the machine-readable artifact; this is the reviewable one. It contains aggregate gate
 * results and per-lane telemetry only. Per-case labels, state, credentials, and provider bodies are never
 * rendered into evidence. The renderer is a pure function of the summary: no clock, no I/O, no configuration,
 * so it is trivially testable and safe to call from the opt-in admin surface.
 */
public final class DecisionEvidenceReport {

    private DecisionEvidenceReport() {}

    /** Renders the summary as a Markdown document. Never returns {@code null}. */
    public static String toMarkdown(DecisionEvidenceSummary summary) {
        Objects.requireNonNull(summary, "summary");
        StringBuilder md = new StringBuilder(4096);
        appendHeader(md, summary);
        appendGates(md, summary);
        appendLanes(md, summary);
        return md.toString();
    }

    private static void appendHeader(StringBuilder md, DecisionEvidenceSummary summary) {
        long evaluated = summary.results().stream()
                .filter(DecisionEvidenceCaseResult::evaluated)
                .count();
        long excluded = summary.results().stream()
                .filter(DecisionEvidenceCaseResult::skippedNoDecision)
                .count();
        md.append("# Decision-plane evidence report — corpus v")
                .append(summary.corpusVersion())
                .append("\n\n");
        md.append("- Cases: ").append(summary.caseCount()).append('\n');
        md.append("- Evaluated: ").append(evaluated).append('\n');
        md.append("- Excluded (outside the declared scope): ").append(excluded).append("\n\n");
    }

    private static void appendGates(StringBuilder md, DecisionEvidenceSummary summary) {
        md.append("## Gates\n\n");
        md.append("| Metric | Result | Rate | Verdict | Note |\n");
        md.append("| --- | --- | --- | --- | --- |\n");
        appendMetric(md, "M1 false-allow", summary.m1(), summary.m1().passed() ? "PASS" : "FAIL");
        appendMetric(md, "M2 routing error", summary.m2(), summary.m2().passed() ? "PASS" : "FAIL");
        appendMetric(md, "M3 abstention", summary.m3(), "report-only");
        md.append('\n');
    }

    private static void appendMetric(
            StringBuilder md, String name, DecisionEvidenceSummary.MetricResult metric, String verdict) {
        md.append("| ")
                .append(cell(name))
                .append(" | ")
                .append(metric.numerator())
                .append('/')
                .append(metric.denominator())
                .append(" | ")
                .append(percent(metric.rate()))
                .append(" | ")
                .append(cell(verdict))
                .append(" | ")
                .append(cell(metric.note()))
                .append(" |\n");
    }

    private static void appendLanes(StringBuilder md, DecisionEvidenceSummary summary) {
        md.append("## Latency (M4)\n\n");
        if (summary.m4().isEmpty()) {
            md.append("_No lane reported._\n\n");
        } else {
            md.append("| Lane | p95 (ms) | Budget (ms) | Within | Samples | Decisions |\n");
            md.append("| --- | --- | --- | --- | --- | --- |\n");
            for (Map.Entry<String, DecisionEvidenceSummary.LaneLatency> entry :
                    summary.m4().entrySet()) {
                DecisionEvidenceSummary.LaneLatency lane = entry.getValue();
                md.append("| ")
                        .append(cell(lane.lane()))
                        .append(" | ")
                        .append(millis(lane.observedP95Nanos()))
                        .append(" | ")
                        .append(millis(lane.budgetNanos()))
                        .append(" | ")
                        .append(yesNo(lane.within()))
                        .append(" | ")
                        .append(lane.sampleCount())
                        .append(" | ")
                        .append(lane.totalDecisions())
                        .append(" |\n");
            }
            md.append('\n');
        }

        md.append("## Cost per 1,000 (M5)\n\n");
        if (summary.m5().isEmpty()) {
            md.append("_No lane reported._\n\n");
        } else {
            md.append("| Lane | Cost / 1,000 | Ceiling | Within | Priced | Decisions | Note |\n");
            md.append("| --- | --- | --- | --- | --- | --- | --- |\n");
            for (Map.Entry<String, DecisionEvidenceSummary.LaneCost> entry :
                    summary.m5().entrySet()) {
                DecisionEvidenceSummary.LaneCost cost = entry.getValue();
                md.append("| ")
                        .append(cell(cost.lane()))
                        .append(" | ")
                        .append(cost.costPerThousand() == null ? "unpriced" : usd(cost.costPerThousand()))
                        .append(" | ")
                        .append(cost.ceiling() == null ? "—" : usd(cost.ceiling()))
                        .append(" | ")
                        .append(costWithin(cost))
                        .append(" | ")
                        .append(cost.pricedDecisions())
                        .append(" | ")
                        .append(cost.totalDecisions())
                        .append(" | ")
                        .append(cell(cost.note()))
                        .append(" |\n");
            }
            md.append('\n');
        }
    }

    private static String costWithin(DecisionEvidenceSummary.LaneCost cost) {
        return cost.ceiling() == null ? "n/a" : yesNo(cost.within());
    }

    /** Escapes a table cell: a raw {@code |} would split the row, and a newline would break the table. */
    private static String cell(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        return value.replace("|", "\\|").replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String percent(double rate) {
        return String.format(Locale.ROOT, "%.2f%%", rate * 100.0);
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0);
    }

    private static String usd(BigDecimal value) {
        return "$" + value.stripTrailingZeros().toPlainString();
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }
}
