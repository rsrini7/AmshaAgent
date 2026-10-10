package com.amsha.core.classifier.evaluation;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Renders a {@link DecisionEvidenceSummary} as a Markdown report.
 *
 * <p>The default report carries run provenance, aggregate gate results, and per-lane telemetry.
 * Opt-in diagnostics also remain aggregate, with disposition counts and provider/model/token
 * coverage only. Case identifiers, labels, decisions, inputs, and error details are never rendered.
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
        appendDiagnostics(md, summary);
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
        appendAttribution(md, summary.attribution());
        md.append("- Cases: ").append(summary.caseCount()).append('\n');
        md.append("- Evaluated: ").append(evaluated).append('\n');
        md.append("- Excluded (outside the declared scope): ").append(excluded).append("\n\n");
    }

    /**
     * Renders the run's provenance: the identifier that tells this run apart from any other, and the
     * provider selection that served it.
     *
     * <p>Every value is an identifier, so this is safe in a committed artifact. An unresolved selection
     * renders as an em dash rather than being omitted, so a reader can see that nothing resolved instead
     * of inferring it from an absent line.
     */
    private static void appendAttribution(StringBuilder md, DecisionEvidenceRunAttribution attribution) {
        md.append("- Run: ").append(cell(attribution.runId())).append('\n');
        md.append("- Provider: ").append(cell(attribution.providerId())).append('\n');
        md.append("- Configured model: ")
                .append(cell(attribution.configuredModel()))
                .append('\n');
        md.append("- Lane: ").append(cell(attribution.lane())).append("\n\n");
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

    /** Renders opt-in aggregate diagnostics, or nothing when they were not projected. */
    private static void appendDiagnostics(StringBuilder md, DecisionEvidenceSummary summary) {
        DecisionEvidenceDiagnostics diagnostics = summary.diagnostics();
        if (diagnostics == null) {
            return;
        }
        md.append("## Aggregate diagnostics (opt-in)\n\n");
        md.append("| Disposition | Cases |\n| --- | ---: |\n");
        md.append("| Evaluated | ").append(diagnostics.evaluatedCaseCount()).append(" |\n");
        md.append("| Refused | ").append(diagnostics.refusedCaseCount()).append(" |\n");
        md.append("| Transform errors | ")
                .append(diagnostics.transformErrorCaseCount())
                .append(" |\n");
        md.append("| Skipped | ").append(diagnostics.skippedCaseCount()).append(" |\n\n");

        if (diagnostics.providerUsage().isEmpty()) {
            md.append("_No provider telemetry was reported._\n\n");
            return;
        }
        md.append("### Provider telemetry\n\n");
        md.append(
                "| Provider | Reported model | Lane | Evaluated | Input token reports | Output token reports | Input tokens | Output tokens |\n");
        md.append("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |\n");
        for (DecisionEvidenceDiagnostics.ProviderUsage usage : diagnostics.providerUsage()) {
            md.append("| ")
                    .append(cell(usage.providerId()))
                    .append(" | ")
                    .append(cell(usage.model()))
                    .append(" | ")
                    .append(cell(usage.lane()))
                    .append(" | ")
                    .append(usage.evaluatedCaseCount())
                    .append(" | ")
                    .append(usage.inputTokenReportedCaseCount())
                    .append(" | ")
                    .append(usage.outputTokenReportedCaseCount())
                    .append(" | ")
                    .append(usage.totalInputTokens())
                    .append(" | ")
                    .append(usage.totalOutputTokens())
                    .append(" |\n");
        }
        md.append('\n');
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
