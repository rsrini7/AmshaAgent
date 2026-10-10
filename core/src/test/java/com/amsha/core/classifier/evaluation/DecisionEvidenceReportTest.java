package com.amsha.core.classifier.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.classifier.api.DecisionLatencyBudget;
import com.amsha.core.config.DecisionProviderConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The Markdown renderer is a pure function of the summary, so it is checked without a runner,
 * provider, or container: aggregate gate verdicts, per-lane tables, and exclusion of case details.
 */
class DecisionEvidenceReportTest {

    private static final String LANE = "SELF_HOSTED";

    /** Two SECURITY tool-risk cases (one correct, one false-allow), one ambiguous and one clear intent. */
    private static List<DecisionEvidenceCaseResult> cases() {
        return List.of(
                result("tr-block", "tool_risk", "safe", "BLOCK", "SECURITY", false, true, "BLOCK", "BLOCK", null, true),
                result(
                        "tr-allow",
                        "tool_risk",
                        "unsafe",
                        "REQUIRE_APPROVAL",
                        "SECURITY",
                        false,
                        true,
                        "LOW_RISK",
                        "ALLOW",
                        null,
                        false),
                result(
                        "in-amb",
                        "intent",
                        "malformed_partial_payload",
                        "NO_OPINION",
                        "BENIGN",
                        true,
                        false,
                        null,
                        "NO_OPINION",
                        null,
                        true),
                result(
                        "in-clear",
                        "intent",
                        "clear",
                        "coder",
                        "BENIGN",
                        false,
                        true,
                        "coder",
                        "DIRECT_ROUTE",
                        "coder",
                        true));
    }

    /** The aggregate-only form: what a committed evidence artifact is allowed to contain. */
    private static DecisionEvidenceSummary summary() {
        return summarizeWith(DecisionEvidenceRunAttribution.UNATTRIBUTED, cases(), false);
    }

    /** The opt-in form, which additionally carries aggregate operational diagnostics. */
    private static DecisionEvidenceSummary summaryWithDiagnostics() {
        return summarizeWith(DecisionEvidenceRunAttribution.UNATTRIBUTED, cases(), true);
    }

    private static DecisionEvidenceSummary summarizeWith(
            DecisionEvidenceRunAttribution attribution,
            List<DecisionEvidenceCaseResult> results,
            boolean includeDiagnostics) {
        DecisionProviderConfig config = Mockito.mock(DecisionProviderConfig.class);
        Mockito.when(config.slots()).thenReturn(Map.of());
        return DecisionEvidenceSummary.summarize(
                1,
                attribution,
                results,
                new DecisionPriceScheduleRegistry(Map.of()),
                config,
                DecisionLatencyBudget.ADR_8_3,
                includeDiagnostics);
    }

    private static DecisionEvidenceCaseResult result(
            String id,
            String task,
            String partition,
            String expected,
            String risk,
            boolean ambiguous,
            boolean opinion,
            String observed,
            String action,
            String specialist,
            boolean correct) {
        return new DecisionEvidenceCaseResult(
                id,
                task,
                partition,
                "decision-1",
                expected,
                risk,
                ambiguous,
                opinion,
                observed,
                opinion ? null : "NO_SPECIALIST",
                LANE,
                "prov",
                "model",
                5_000_000L,
                100L,
                20L,
                false,
                null,
                false,
                null,
                false,
                null,
                correct,
                action,
                specialist,
                action);
    }

    @Test
    void rendersTheHeaderAndTheScalarGates() {
        String md = DecisionEvidenceReport.toMarkdown(summary());

        assertTrue(md.contains("# Decision-plane evidence report — corpus v1"), md);
        assertTrue(md.contains("- Cases: 4"), md);
        assertTrue(md.contains("- Evaluated: 4"), md);
        // M1: 1 false-allow of 2 SECURITY cases -> the gate fails.
        assertTrue(md.contains("| M1 false-allow | 1/2 | 50.00% | FAIL |"), md);
        // M2: no routing error over the single ambiguous case -> the gate passes.
        assertTrue(md.contains("| M2 routing error | 0/1 | 0.00% | PASS |"), md);
        // M3 is report-only and has no verdict.
        assertTrue(md.contains("| M3 abstention | 1/4 | 25.00% | report-only |"), md);
    }

    @Test
    void rendersThePerLaneLatencyAndCostTables() {
        String md = DecisionEvidenceReport.toMarkdown(summary());

        assertTrue(md.contains("## Latency (M4)"), md);
        // 5 ms observed against the 50 ms local budget, all four samples measured.
        assertTrue(md.contains("| " + LANE + " | 5.00 | 50.00 | yes | 4 | 4 |"), md);
        assertTrue(md.contains("## Cost per 1,000 (M5)"), md);
        // The shipped schedule is empty, so the lane is unpriced and no ceiling is assessable.
        assertTrue(md.contains("unpriced"), md);
        assertTrue(md.contains("| n/a |"), md);
    }

    @Test
    void keepsPerCaseLabelsAndIdentifiersOutOfTheMarkdownArtifact() {
        String md = DecisionEvidenceReport.toMarkdown(summary());
        assertFalse(md.contains("## Cases"), md);
        assertFalse(md.contains("tr-allow"), md);
        assertFalse(md.contains("REQUIRE_APPROVAL"), md);
        assertFalse(md.contains("DIRECT_ROUTE"), md);
        assertFalse(md.contains("NO_SPECIALIST"), md);
    }

    @Test
    void jsonSummaryExposesAggregateMetricsWithoutPerCaseResults() throws JsonProcessingException {
        String json = new ObjectMapper().writeValueAsString(summary());
        assertTrue(json.contains("\"m1\""), json);
        assertFalse(json.contains("\"results\""), json);
        assertFalse(json.contains("tr-allow"), json);
        assertFalse(json.contains("REQUIRE_APPROVAL"), json);
    }

    @Test
    void aggregateMarkdownDoesNotRenderCaseDetails() {
        String md = DecisionEvidenceReport.toMarkdown(summary());
        assertTrue(md.contains("## Gates"), md);
        assertTrue(md.contains("## Latency (M4)"), md);
        assertTrue(md.contains("## Cost per 1,000 (M5)"), md);
        assertFalse(md.contains("unsafe"), md);
        assertFalse(md.contains("coder"), md);
        assertFalse(md.contains("NO_SPECIALIST"), md);
    }

    @Test
    void theReportCarriesNothingBeyondThePayloadFreeSummary() {
        String md = DecisionEvidenceReport.toMarkdown(summary());

        // A guard against a future field being added to the report: none of these may ever appear.
        for (String forbidden : List.of("stateJson", "apiKey", "credential", "user_message", "Authorization")) {
            assertFalse(md.toLowerCase().contains(forbidden.toLowerCase()), forbidden + " leaked into: " + md);
        }
    }

    @Test
    void aNullSummaryIsRejected() {
        assertThrows(NullPointerException.class, () -> DecisionEvidenceReport.toMarkdown(null));
    }

    @Test
    void rendersTheRunAttributionSoTwoRunsCanBeToldApart() {
        String md = DecisionEvidenceReport.toMarkdown(summarizeWith(
                new DecisionEvidenceRunAttribution("run-abc", "typesafe", "ts-model-v1", "REMOTE"), cases(), false));

        assertTrue(md.contains("- Run: run-abc"), md);
        assertTrue(md.contains("- Provider: typesafe"), md);
        assertTrue(md.contains("- Configured model: ts-model-v1"), md);
        assertTrue(md.contains("- Lane: REMOTE"), md);
    }

    @Test
    void anUnresolvedAttributionRendersAsAnEmDashRatherThanDisappearing() {
        // A missing line would read as "no attribution was attempted"; an em dash reads as "nothing
        // resolved", which is the distinction an operator investigating a refused run needs.
        String md = DecisionEvidenceReport.toMarkdown(summary());

        assertTrue(md.contains("- Run: —"), md);
        assertTrue(md.contains("- Provider: —"), md);
        assertTrue(md.contains("- Configured model: —"), md);
        assertTrue(md.contains("- Lane: —"), md);
    }

    @Test
    void rendersAggregateDiagnosticsOnlyWhenTheyWereProjected() {
        String aggregateOnly = DecisionEvidenceReport.toMarkdown(summary());
        assertFalse(aggregateOnly.contains("## Aggregate diagnostics"), aggregateOnly);

        String markdown = DecisionEvidenceReport.toMarkdown(summaryWithDiagnostics());
        assertTrue(markdown.contains("## Aggregate diagnostics (opt-in)"), markdown);
        assertTrue(markdown.contains("| Evaluated | 4 |"), markdown);
        assertTrue(markdown.contains("### Provider telemetry"), markdown);
        assertTrue(markdown.contains("| prov | model | SELF_HOSTED | 4 | 4 | 4 | 400 | 80 |"), markdown);
        assertFalse(markdown.contains("tr-allow"), markdown);
        assertFalse(markdown.contains("REQUIRE_APPROVAL"), markdown);
        assertFalse(markdown.contains("LOW_RISK"), markdown);
    }

    @Test
    void jsonSummaryHasExactAggregateDiagnosticShapeAndNoCaseData() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();

        JsonNode aggregateOnly = mapper.readTree(mapper.writeValueAsString(summary()));
        assertEquals(
                Set.of("corpusVersion", "attribution", "caseCount", "m1", "m2", "m3", "m4", "m5"),
                fieldNames(aggregateOnly));

        JsonNode withDiagnostics = mapper.readTree(mapper.writeValueAsString(summaryWithDiagnostics()));
        assertEquals(
                Set.of("corpusVersion", "attribution", "caseCount", "diagnostics", "m1", "m2", "m3", "m4", "m5"),
                fieldNames(withDiagnostics));
        JsonNode diagnostics = withDiagnostics.get("diagnostics");
        assertEquals(
                Set.of(
                        "evaluatedCaseCount",
                        "refusedCaseCount",
                        "transformErrorCaseCount",
                        "skippedCaseCount",
                        "providerUsage"),
                fieldNames(diagnostics));
        assertEquals(1, diagnostics.get("providerUsage").size());
        assertEquals(
                Set.of(
                        "providerId",
                        "model",
                        "lane",
                        "evaluatedCaseCount",
                        "inputTokenReportedCaseCount",
                        "outputTokenReportedCaseCount",
                        "totalInputTokens",
                        "totalOutputTokens"),
                fieldNames(diagnostics.get("providerUsage").get(0)));
        assertFalse(withDiagnostics.has("results"));
        assertFalse(withDiagnostics.toString().contains("caseId"));
        assertFalse(withDiagnostics.toString().contains("expectedLabel"));
        assertFalse(withDiagnostics.toString().contains("observedLabel"));
        assertFalse(withDiagnostics.toString().contains("policyResolution"));
        assertFalse(withDiagnostics.toString().contains("detail"));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
