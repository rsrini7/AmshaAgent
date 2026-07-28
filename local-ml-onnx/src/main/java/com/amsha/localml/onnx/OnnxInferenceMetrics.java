package com.amsha.localml.onnx;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Bounded-cardinality, payload-free metrics for local ONNX embedding execution. */
@ApplicationScoped
public class OnnxInferenceMetrics {

    private final MeterRegistry registry;
    private Timer embeddingLatency;

    @Inject
    public OnnxInferenceMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @PostConstruct
    void initialize() {
        embeddingLatency = Timer.builder("amsha.onnx.embedding.latency")
                .description("Local ONNX embedding request latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    void recordEmbedding(String outcome, long elapsedNanos) {
        Counter.builder("amsha.onnx.embedding.requests")
                .tag("outcome", outcome)
                .description("Local ONNX embedding request outcomes")
                .register(registry)
                .increment();
        embeddingLatency.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    void recordWarmup(String outcome) {
        Counter.builder("amsha.onnx.warmup")
                .tag("outcome", outcome)
                .description("Local ONNX warm-up outcomes")
                .register(registry)
                .increment();
    }
}
