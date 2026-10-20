package com.amsha.core.classifier;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;

import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.QuestionKey;

/**
 * Shared boundary adapter extracting and validating classifier results from a {@link DecisionResult}.
 * Enforces request identity, closed option sets, bound answer lookup, and confidence preservation.
 */
final class DecisionShadowResultAdapter {

    private DecisionShadowResultAdapter() {}

    /**
     * Adapts a raw {@link DecisionResult} into a typed {@link ClassifierResult}.
     *
     * @param <T> the predicted value type
     * @param request the originating decision request
     * @param result the evaluation result from the decision engine
     * @param boundKey the bound question key to extract
     * @param allowedOptions closed set of valid choice labels
     * @param valueMapper mapping function converting choice to typed value
     * @return typed opinion or fail-closed no-opinion
     */
    static <T> ClassifierResult<T> adapt(
            DecisionRequest request,
            DecisionResult result,
            QuestionKey<Choice> boundKey,
            Collection<String> allowedOptions,
            Function<Choice, T> valueMapper) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(boundKey, "boundKey");
        Objects.requireNonNull(allowedOptions, "allowedOptions");
        Objects.requireNonNull(valueMapper, "valueMapper");

        if (result == null) {
            return new ClassifierResult.NoOpinion<>(NoOpinionReason.INFERENCE_ERROR);
        }

        if (result instanceof DecisionResult.Opinion opinion) {
            if (!request.decisionId().equals(opinion.decisionId())
                    || request.schemaVersion() != opinion.schemaVersion()) {
                return new ClassifierResult.NoOpinion<>(NoOpinionReason.INFERENCE_ERROR);
            }

            Choice choice;
            try {
                choice = opinion.get(boundKey);
            } catch (IllegalArgumentException | ClassCastException _) {
                return new ClassifierResult.NoOpinion<>(NoOpinionReason.INFERENCE_ERROR);
            }

            if (choice == null || !allowedOptions.contains(choice.label())) {
                return new ClassifierResult.NoOpinion<>(NoOpinionReason.INFERENCE_ERROR);
            }

            T value;
            try {
                value = valueMapper.apply(choice);
            } catch (RuntimeException _) {
                return new ClassifierResult.NoOpinion<>(NoOpinionReason.INFERENCE_ERROR);
            }

            if (value == null) {
                return new ClassifierResult.NoOpinion<>(NoOpinionReason.INFERENCE_ERROR);
            }

            if (choice.confidence().isEmpty()) {
                return new ClassifierResult.NoOpinion<>(NoOpinionReason.LOW_CONFIDENCE);
            }

            String rule = opinion.ruleId();
            if (rule == null || rule.isBlank()) {
                return new ClassifierResult.NoOpinion<>(NoOpinionReason.INFERENCE_ERROR);
            }

            float conf = (float) choice.confidence().getAsDouble();
            return new ClassifierResult.Opinion<>(value, conf, rule);
        } else if (result instanceof DecisionResult.NoOpinion noOp) {
            return new ClassifierResult.NoOpinion<>(noOp.reason());
        }

        return new ClassifierResult.NoOpinion<>(NoOpinionReason.INFERENCE_ERROR);
    }
}
