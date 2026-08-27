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
    private Timer scoringLatency;

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
        scoringLatency = Timer.builder("amsha.onnx.scoring.latency")
                .description("Local ONNX scoring/reranking request latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    void recordEmbedding(String outcome, long elapsedNanos) {
        Counter.builder("amsha.onnx.embedding.requests")
                .tag("outcome", outcome)
                .description("Local ONNX embedding request outcomes")
                .register(registry)
                .increment();
        if (embeddingLatency != null) {
            embeddingLatency.record(elapsedNanos, TimeUnit.NANOSECONDS);
        } else {
            Timer.builder("amsha.onnx.embedding.latency")
                    .description("Local ONNX embedding request latency")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
        }
    }

    void recordPhase(String phase, long elapsedNanos) {
        Timer.builder("amsha.onnx.embedding.phase.latency")
                .tag("phase", phase)
                .description("Local ONNX embedding execution phase latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    void recordExecutionStage(String stage) {
        Counter.builder("amsha.onnx.embedding.execution.stage")
                .tag("stage", stage)
                .description("Payload-free checkpoints for local ONNX embedding execution")
                .register(registry)
                .increment();
    }

    void recordWarmup(String outcome) {
        Counter.builder("amsha.onnx.warmup")
                .tag("outcome", outcome)
                .description("Local ONNX warm-up outcomes")
                .register(registry)
                .increment();
    }

    void recordScoring(String outcome, long elapsedNanos) {
        Counter.builder("amsha.onnx.scoring.requests")
                .tag("outcome", outcome)
                .description("Local ONNX scoring/reranking request outcomes")
                .register(registry)
                .increment();
        if (scoringLatency != null) {
            scoringLatency.record(elapsedNanos, TimeUnit.NANOSECONDS);
        } else {
            Timer.builder("amsha.onnx.scoring.latency")
                    .description("Local ONNX scoring/reranking request latency")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
        }
    }

    void recordScoringPhase(String phase, long elapsedNanos) {
        Timer.builder("amsha.onnx.scoring.phase.latency")
                .tag("phase", phase)
                .description("Local ONNX scoring execution phase latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    void recordScoringExecutionStage(String stage) {
        Counter.builder("amsha.onnx.scoring.execution.stage")
                .tag("stage", stage)
                .description("Payload-free checkpoints for local ONNX scoring execution")
                .register(registry)
                .increment();
    }

    void recordTruncation(String component) {
        Counter.builder("amsha.localml.onnx.truncation")
                .tag("component", component)
                .description("Payload-free counter for text inputs exceeding maxSequenceLength")
                .register(registry)
                .increment();
    }
}
