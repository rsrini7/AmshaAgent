package com.amsha.core.classifier.evaluation;

import java.util.Objects;

import com.amsha.classifier.api.DecisionMetadata;
import com.amsha.classifier.api.IntentDecisionPolicy;

/**
 * One case's observed outcome during an evidence run, payload-free.
 *
 * <p>It carries answer labels and telemetry only — never the projected state, a credential, or a
 * provider body — so a summary can be emitted without leaking what an evidence run actually submitted.
 * A case resolves to exactly one of four dispositions, each with its own factory:
 *
 * <ul>
 *   <li>{@link #fromOutcome} — the engine returned an opinion or a no-opinion;</li>
 *   <li>{@link #refused} — the service refused before egress (switch off, unknown decision, no provider);</li>
 *   <li>{@link #transformError} — the runner could not build the request state (for example a malformed
 *       tool-risk context); the system failed closed, which is the safe outcome for an abstention-expected
 *       case;</li>
 *   <li>{@link #skippedNoDecision} — the case is outside the run's scope: its task has no registered
 *       decision, or the corpus did not declare that task in {@code evaluatedTasks}.</li>
 * </ul>
 */
public record DecisionEvidenceCaseResult(
        String caseId,
        String task,
        String partition,
        String decisionId,
        String expectedLabel,
        String riskCategory,
        boolean baselineAmbiguous,
        boolean observedOpinion,
        String observedLabel,
        String noOpinionReason,
        String lane,
        String providerId,
        String model,
        long latencyNanos,
        Long inputTokens,
        Long outputTokens,
        boolean refused,
        String refusalReason,
        boolean transformError,
        String transformErrorMessage,
        boolean skippedNoDecision,
        String skipReason,
        boolean correct,
        String intentPolicyAction,
        String resolvedSpecialist,
        String toolRiskPolicyAction) {

    public DecisionEvidenceCaseResult(
            String caseId,
            String task,
            String partition,
            String decisionId,
            String expectedLabel,
            String riskCategory,
            boolean baselineAmbiguous,
            boolean observedOpinion,
            String observedLabel,
            String noOpinionReason,
            String lane,
            String providerId,
            String model,
            long latencyNanos,
            Long inputTokens,
            Long outputTokens,
            boolean refused,
            String refusalReason,
            boolean transformError,
            String transformErrorMessage,
            boolean skippedNoDecision,
            String skipReason,
            boolean correct) {
        this(
                caseId,
                task,
                partition,
                decisionId,
                expectedLabel,
                riskCategory,
                baselineAmbiguous,
                observedOpinion,
                observedLabel,
                noOpinionReason,
                lane,
                providerId,
                model,
                latencyNanos,
                inputTokens,
                outputTokens,
                refused,
                refusalReason,
                transformError,
                transformErrorMessage,
                skippedNoDecision,
                skipReason,
                correct,
                null,
                null,
                null);
    }

    /** Builds a result from a successful (or no-opinion) evaluation outcome. */
    public static DecisionEvidenceCaseResult fromOutcome(
            DecisionEvidenceCase c, String decisionId, DecisionEvaluationOutcome o, String observedLabel) {
        long latencyNanos =
                o.metadata().latency() == null ? 0L : o.metadata().latency().toNanos();
        return fromOutcome(c, decisionId, o, observedLabel, latencyNanos);
    }

    /** Builds a result using the runner's measured end-to-end latency for this decision. */
    public static DecisionEvidenceCaseResult fromOutcome(
            DecisionEvidenceCase c,
            String decisionId,
            DecisionEvaluationOutcome o,
            String observedLabel,
            long measuredLatencyNanos) {
        Objects.requireNonNull(c, "case");
        Objects.requireNonNull(o, "outcome");
        if (measuredLatencyNanos < 0) {
            throw new IllegalArgumentException("measuredLatencyNanos must not be negative");
        }
        DecisionMetadata md = o.metadata();
        return new DecisionEvidenceCaseResult(
                c.id(),
                c.task(),
                c.partition(),
                decisionId,
                c.expectedLabel(),
                c.riskCategory(),
                c.isBaselineAmbiguous(),
                o.opinion(),
                observedLabel,
                o.opinion() ? null : o.noOpinionReason(),
                o.lane(),
                o.providerId(),
                o.model(),
                measuredLatencyNanos,
                md.inputTokens(),
                md.outputTokens(),
                false,
                null,
                false,
                null,
                false,
                null,
                correctness(c, o.opinion(), observedLabel),
                o.intentPolicyResolution() == null
                        ? null
                        : o.intentPolicyResolution().action().name(),
                o.intentPolicyResolution() == null
                        ? null
                        : o.intentPolicyResolution().selectedRole().orElse(null),
                o.toolRiskPolicyResolution() == null
                        ? null
                        : o.toolRiskPolicyResolution().effectiveAction() == null
                                ? "ALLOW"
                                : o.toolRiskPolicyResolution().effectiveAction().name());
    }

    public boolean evaluated() {
        if (refused || transformError || skippedNoDecision) {
            return false;
        }
        if (DecisionEvidenceCase.TASK_INTENT.equals(task)) {
            return intentPolicyAction != null;
        }
        return DecisionEvidenceCase.TASK_TOOL_RISK.equals(task) && toolRiskPolicyAction != null;
    }

    /** Whether the evaluated provider explicitly abstained or returned no opinion. */
    public boolean abstained() {
        return !observedOpinion || IntentDecisionPolicy.NO_OPINION_LABEL.equals(observedLabel);
    }

    /** The service refused before any egress; the system abstained, which is safe for an abstention case. */
    public static DecisionEvidenceCaseResult refused(DecisionEvidenceCase c, String decisionId, String reason) {
        return new DecisionEvidenceCaseResult(
                c.id(),
                c.task(),
                c.partition(),
                decisionId,
                c.expectedLabel(),
                c.riskCategory(),
                c.isBaselineAmbiguous(),
                false,
                null,
                null,
                null,
                null,
                null,
                0L,
                null,
                null,
                true,
                reason,
                false,
                null,
                false,
                null,
                c.isAbstentionExpected());
    }

    /** The runner could not build the request state; the system failed closed. */
    public static DecisionEvidenceCaseResult transformError(DecisionEvidenceCase c, String decisionId, String message) {
        return new DecisionEvidenceCaseResult(
                c.id(),
                c.task(),
                c.partition(),
                decisionId,
                c.expectedLabel(),
                c.riskCategory(),
                c.isBaselineAmbiguous(),
                false,
                null,
                null,
                null,
                null,
                null,
                0L,
                null,
                null,
                false,
                null,
                true,
                message,
                false,
                null,
                c.isAbstentionExpected());
    }

    /**
     * The case is outside the run's scope — its task is not in the corpus's declared evaluated tasks, or has
     * no registered decision — so nothing was evaluated.
     */
    public static DecisionEvidenceCaseResult skippedNoDecision(DecisionEvidenceCase c, String reason) {
        return new DecisionEvidenceCaseResult(
                c.id(),
                c.task(),
                c.partition(),
                null,
                c.expectedLabel(),
                c.riskCategory(),
                c.isBaselineAmbiguous(),
                false,
                null,
                null,
                null,
                null,
                null,
                0L,
                null,
                null,
                false,
                null,
                false,
                null,
                true,
                reason,
                false);
    }

    private static boolean correctness(DecisionEvidenceCase c, boolean opinion, String observed) {
        if (c.isAbstentionExpected()) {
            return !opinion;
        }
        if (!opinion) {
            return false;
        }
        if (c.acceptableLabels() != null && !c.acceptableLabels().isEmpty()) {
            return c.acceptableLabels().contains(observed);
        }
        return c.expectedLabel().equals(observed);
    }
}
