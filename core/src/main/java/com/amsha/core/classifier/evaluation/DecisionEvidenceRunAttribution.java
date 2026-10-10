package com.amsha.core.classifier.evaluation;

/**
 * Identifies the provenance of one evidence run: which run it was, and which provider, lane, and
 * configured model the selection resolved to.
 *
 * <p>Two responses from {@code POST /admin/decision/evidence/run} run the corpus independently, so their
 * measurements — notably the M4 p95, which is runner-measured wall-clock — differ and cannot be told
 * apart or combined without an identifier. This is that identifier, plus the provider attribution the
 * aggregate metrics otherwise lack.
 *
 * <p>The model here is the <em>configured</em> selection, not the telemetry model a provider reports on
 * each response. The two can differ, and only the reported one is what a price schedule is keyed by, so
 * the reported model is deliberately not asserted here: it is known per case, and appears in the opt-in
 * diagnostics. Keeping the configured value in the attribution and the observed value per case is what
 * lets an operator see a mismatch instead of having it collapsed into one field.
 *
 * <p>Identifiers and a lane name only — no credential material, no endpoint path, no payload — so it is
 * safe in a committed evidence artifact.
 *
 * @param runId the identifier of this run; unique per invocation
 * @param providerId the selected provider id, or the configured id when the selection did not resolve
 * @param configuredModel the model the selection was configured with, or {@code null} when none resolved
 * @param lane the selected governance lane ({@code REMOTE} / {@code SELF_HOSTED}), or {@code null} when
 *     the selection did not resolve
 */
public record DecisionEvidenceRunAttribution(String runId, String providerId, String configuredModel, String lane) {

    /**
     * A summary built without a run: metric-maths callers and unit tests construct summaries directly,
     * and a synthetic summary has no provider selection and no run identifier.
     */
    public static final DecisionEvidenceRunAttribution UNATTRIBUTED =
            new DecisionEvidenceRunAttribution(null, null, null, null);

    public DecisionEvidenceRunAttribution {
        runId = blankToNull(runId);
        providerId = blankToNull(providerId);
        configuredModel = blankToNull(configuredModel);
        lane = blankToNull(lane);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
