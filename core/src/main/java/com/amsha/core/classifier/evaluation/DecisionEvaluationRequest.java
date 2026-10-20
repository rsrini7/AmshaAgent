package com.amsha.core.classifier.evaluation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A caller's request to run one registered decision.
 *
 * <p><b>It carries a decision id and state, never a policy.</b> Everything that bounds egress —
 * retention, redaction, the projection allow-list, {@code allowedProviders}, {@code failMode} —
 * comes from the registered {@link RegisteredDecision} and the provider's governance declaration.
 * The only thing a request may influence is the option set of a question the registry marked
 * {@code CALLER_SUPPLIED}, because those options are runtime data rather than policy.
 *
 * <p>This is what keeps the endpoint from being a generic "send any state anywhere" primitive: a
 * request cannot widen the allow-list, cannot lower the retention, and cannot name a provider.
 *
 * @param decisionId the registered decision to run; must resolve in the registry
 * @param stateJson the raw state projection source, before allow-listing
 * @param choiceOptions options per {@code CALLER_SUPPLIED} question handle; empty when none
 * @param timeout an optional per-request deadline; the definition's timeout is the ceiling
 */
public record DecisionEvaluationRequest(
        String decisionId, String stateJson, Map<String, List<String>> choiceOptions, Optional<Duration> timeout) {

    public DecisionEvaluationRequest {
        decisionId = requireText(decisionId, "decisionId");
        stateJson = requireText(stateJson, "stateJson");
        Map<String, List<String>> optionsCopy = new LinkedHashMap<>();
        if (choiceOptions != null) {
            for (Map.Entry<String, List<String>> entry : choiceOptions.entrySet()) {
                String handle = requireText(entry.getKey(), "choice option handle");
                List<String> values = new ArrayList<>();
                Set<String> seenValues = new HashSet<>();
                if (entry.getValue() != null) {
                    for (String value : entry.getValue()) {
                        if (value == null || value.isBlank()) {
                            throw new IllegalArgumentException("choice option for '" + handle + "' must not be blank");
                        }
                        String normalized = value.trim();
                        if (!seenValues.add(normalized)) {
                            throw new IllegalArgumentException("duplicate choice option for handle '" + handle + "'");
                        }
                        values.add(normalized);
                    }
                }
                optionsCopy.put(handle, List.copyOf(values));
            }
        }
        choiceOptions = Map.copyOf(optionsCopy);
        timeout = nonNull(timeout, "timeout");
        timeout.ifPresent(deadline -> {
            if (deadline.isZero() || deadline.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive, got " + deadline);
            }
        });
    }

    /**
     * Convenience factory for a request with no per-request deadline.
     *
     * @param decisionId the registered decision id
     * @param stateJson the raw state projection source
     * @param choiceOptions options per caller-supplied question handle
     * @return the request
     */
    public static DecisionEvaluationRequest of(
            String decisionId, String stateJson, Map<String, List<String>> choiceOptions) {
        return new DecisionEvaluationRequest(decisionId, stateJson, choiceOptions, Optional.empty());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static <T> T nonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    @Override
    public String toString() {
        // Payload-free: never echo state, which is exactly what this path must not leak into a log.
        return "DecisionEvaluationRequest[decisionId=" + decisionId
                + ", stateLength=" + stateJson.length()
                + ", optionHandles=" + choiceOptions.keySet() + "]";
    }
}
