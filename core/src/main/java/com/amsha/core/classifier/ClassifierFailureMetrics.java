package com.amsha.core.classifier;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Bounded, payload-free error metrics shared by active classifier interceptor dispatchers. */
public final class ClassifierFailureMetrics {

    private final Map<NoOpinionReason, Counter> counters;

    public ClassifierFailureMetrics(MeterRegistry registry, String metricName) {
        Objects.requireNonNull(registry, "registry");
        if (metricName == null || metricName.isBlank()) {
            throw new IllegalArgumentException("metricName must not be blank");
        }
        Map<NoOpinionReason, Counter> configured = new EnumMap<>(NoOpinionReason.class);
        configured.put(
                NoOpinionReason.TIMEOUT,
                Counter.builder(metricName).tag("reason", "timeout").register(registry));
        configured.put(
                NoOpinionReason.INFERENCE_ERROR,
                Counter.builder(metricName).tag("reason", "inference_error").register(registry));
        counters = Map.copyOf(configured);
    }

    public NoOpinionReason record(Throwable error) {
        NoOpinionReason reason = failureReason(error);
        counters.get(reason).increment();
        return reason;
    }

    private static NoOpinionReason failureReason(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException) return NoOpinionReason.TIMEOUT;
            current = current.getCause();
        }
        return NoOpinionReason.INFERENCE_ERROR;
    }
}
