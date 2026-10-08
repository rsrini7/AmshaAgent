package com.amsha.core.classifier.evaluation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.amsha.classifier.api.DecisionLatencyBudget;
import com.amsha.core.config.DecisionProviderConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
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
    private DecisionEvidenceSummary summary() {
        return summarize(List.of(
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
                        true)));
    }

    private static DecisionEvidenceSummary summarize(List<DecisionEvidenceCaseResult> results) {
        DecisionProviderConfig config = Mockito.mock(DecisionProviderConfig.class);
        Mockito.when(config.slots()).thenReturn(Map.of());
        return DecisionEvidenceSummary.summarize(
                1, results, new DecisionPriceScheduleRegistry(Map.of()), config, DecisionLatencyBudget.ADR_8_3);
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
}
