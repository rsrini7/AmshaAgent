package com.amsha.core.classifier.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.amsha.classifier.api.DecisionMetadata;
import com.amsha.classifier.api.IntentPolicyResolution;
import com.amsha.classifier.api.ToolRiskDecisionPolicy.PolicyResolution;

/**
 * A payload-free summary of one evaluation.
 *
 * <p><b>It is a summary, not a raw result.</b> The answers map carries labels and confidences —
 * never the projected state, the credential, or a provider response body. A {@code NoOpinion}'s
 * already-sanitized detail is deliberately not propagated; only its categorical reason name is, so a
 * caller can tell "the provider is disabled" from "the model abstained" without any provider text.
 *
 * @param providerId the provider slot that was evaluated
 * @param lane the governance lane it resolved to ({@code REMOTE} or {@code SELF_HOSTED})
 * @param model the model that was requested, or {@code null} when the lane omits it
 * @param opinion whether the engine returned an {@code Opinion} rather than a {@code NoOpinion}
 * @param ruleId the engine's rule id, or {@code null} when it returned none
 * @param noOpinionReason the categorical no-opinion reason name; {@code null} for an opinion
 * @param answers per-question answer summaries (handle &rarr; label and confidence); never state
 * @param metadata provider, model, latency, and token telemetry
 */
public record DecisionEvaluationOutcome(
        String providerId,
        String lane,
        String model,
        boolean opinion,
        String ruleId,
        String noOpinionReason,
        Map<String, String> answers,
        DecisionMetadata metadata,
        IntentPolicyResolution intentPolicyResolution,
        PolicyResolution toolRiskPolicyResolution) {

    public DecisionEvaluationOutcome(
            String providerId,
            String lane,
            String model,
            boolean opinion,
            String ruleId,
            String noOpinionReason,
            Map<String, String> answers,
            DecisionMetadata metadata) {
        this(providerId, lane, model, opinion, ruleId, noOpinionReason, answers, metadata, null, null);
    }

    public DecisionEvaluationOutcome {
        Objects.requireNonNull(providerId, "providerId");
        if (providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        Objects.requireNonNull(lane, "lane");
        if (lane.isBlank()) {
            throw new IllegalArgumentException("lane must not be blank");
        }
        if (model != null && model.isBlank()) {
            model = null;
        }
        if (ruleId != null && ruleId.isBlank()) {
            ruleId = null;
        }
        if (opinion) {
            if (noOpinionReason != null) {
                throw new IllegalArgumentException("an opinion must not carry a no-opinion reason");
            }
        } else if (noOpinionReason == null || noOpinionReason.isBlank()) {
            throw new IllegalArgumentException("a no-opinion outcome must name its categorical reason");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        if (answers != null) {
            for (Map.Entry<String, String> entry : answers.entrySet()) {
                String handle = Objects.requireNonNull(entry.getKey(), "answer handle");
                if (handle.isBlank()) {
                    throw new IllegalArgumentException("answer handle must not be blank");
                }
                copy.put(handle, Objects.requireNonNull(entry.getValue(), "answer for handle " + handle));
            }
        }
        if (!opinion && !copy.isEmpty()) {
            throw new IllegalArgumentException("a no-opinion outcome must not carry answers");
        }
        answers = Map.copyOf(copy);
        if (metadata == null) {
            metadata = DecisionMetadata.empty();
        }
        if (intentPolicyResolution != null && toolRiskPolicyResolution != null) {
            throw new IllegalArgumentException("an evaluation cannot carry both intent and tool-risk policy results");
        }
    }

    /**
     * An opinion outcome.
     *
     * @param providerId the provider slot
     * @param lane the resolved lane name
     * @param model the requested model, or {@code null}
     * @param ruleId the engine's rule id, or {@code null}
     * @param answers per-question answer summaries
     * @param metadata execution telemetry
     * @return the outcome
     */
    public static DecisionEvaluationOutcome opinion(
            String providerId,
            String lane,
            String model,
            String ruleId,
            Map<String, String> answers,
            DecisionMetadata metadata) {
        return new DecisionEvaluationOutcome(providerId, lane, model, true, ruleId, null, answers, metadata);
    }

    /**
     * A no-opinion outcome.
     *
     * @param providerId the provider slot
     * @param lane the resolved lane name
     * @param model the requested model, or {@code null}
     * @param ruleId the engine's rule id, or {@code null}
     * @param noOpinionReason the categorical reason name
     * @return the outcome
     */
    public static DecisionEvaluationOutcome noOpinion(
            String providerId, String lane, String model, String ruleId, String noOpinionReason) {
        return new DecisionEvaluationOutcome(
                providerId, lane, model, false, ruleId, noOpinionReason, Map.of(), DecisionMetadata.empty());
    }

    public DecisionEvaluationOutcome withIntentPolicyResolution(IntentPolicyResolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        return new DecisionEvaluationOutcome(
                providerId, lane, model, opinion, ruleId, noOpinionReason, answers, metadata, resolution, null);
    }

    public DecisionEvaluationOutcome withToolRiskPolicyResolution(PolicyResolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        return new DecisionEvaluationOutcome(
                providerId, lane, model, opinion, ruleId, noOpinionReason, answers, metadata, null, resolution);
    }

    @Override
    public String toString() {
        return "DecisionEvaluationOutcome[provider=" + providerId
                + ", lane=" + lane
                + ", opinion=" + opinion
                + ", answerCount=" + answers.size() + "]";
    }
}
