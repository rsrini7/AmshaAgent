package com.amsha.classifier.api;

import java.util.Objects;

/** Closed result contract for optional classifiers; operational failures are never opinions. */
public sealed interface ClassifierResult<T> permits ClassifierResult.Opinion, ClassifierResult.NoOpinion {

    record Opinion<T>(T value, float confidence, String ruleId) implements ClassifierResult<T> {
        public Opinion {
            Objects.requireNonNull(value, "value");
            if (!Float.isFinite(confidence) || confidence < 0.0f || confidence > 1.0f) {
                throw new IllegalArgumentException("confidence must be finite and in [0, 1]");
            }
            if (ruleId == null || ruleId.isBlank()) {
                throw new IllegalArgumentException("ruleId must not be blank");
            }
        }
    }

    record NoOpinion<T>(NoOpinionReason reason) implements ClassifierResult<T> {
        public NoOpinion {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum NoOpinionReason {
        DISABLED,
        MISSING_MODEL,
        TIMEOUT,
        OVERLOAD_REJECTED,
        CIRCUIT_OPEN,
        LOW_CONFIDENCE,
        INFERENCE_ERROR
    }
}
