package com.amsha.core.classifier;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

import com.amsha.classifier.api.ProviderCategory;
import com.amsha.classifier.api.ToolRiskDecisionPolicy.PolicyResolution;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;

/**
 * Emits payload-free bounded metrics and trace attributes for decision-plane policy resolutions and
 * completed evaluations.
 *
 * <p>Never logs, records, or tags prompt, tool parameter, or state payloads. Every metric tag value
 * is drawn from a closed, bounded set: an action or outcome name, a provider lane, and a coarse
 * confidence or latency bucket. Model identifiers are retained on the active trace only and never
 * become metric tags.
 */
public final class DecisionTelemetry {

    public static final String METRIC_NAME = "amsha.decision.policy.resolutions";

    /** Count of completed evaluations by bounded outcome, lane, model and confidence bucket. */
    public static final String EVALUATION_METRIC_NAME = "amsha.decision.evaluations";

    /** Count of completed evaluations by bounded latency bucket. */
    public static final String LATENCY_METRIC_NAME = "amsha.decision.latency";

    /** Cumulative evaluation cost in USD. */
    public static final String COST_METRIC_NAME = "amsha.decision.cost.usd";

    /** Tag value for a field the provider did not report. */
    public static final String UNKNOWN = "unknown";

    private DecisionTelemetry() {}

    /**
     * Records bounded outcome metrics and enriches the current trace span if active.
     *
     * @param meterRegistry metric registry, or null
     * @param resolution policy resolution metadata
     */
    public static void record(MeterRegistry meterRegistry, PolicyResolution resolution) {
        Objects.requireNonNull(resolution, "resolution");

        String action = resolution.effectiveAction() != null
                ? resolution.effectiveAction().name()
                : "ALLOW";
        String outcome = resolution.fallback() ? "FALLBACK" : action;

        Span currentSpan = Span.current();
        if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
            currentSpan.setAttribute("decision.id", resolution.decisionId());
            currentSpan.setAttribute("decision.schema_version", (long) resolution.schemaVersion());
            currentSpan.setAttribute("decision.action", action);
            currentSpan.setAttribute("decision.escalated", resolution.escalated());
            currentSpan.setAttribute("decision.fallback", resolution.fallback());
            currentSpan.setAttribute("decision.outcome", outcome);
        }

        if (meterRegistry != null) {
            Counter.builder(METRIC_NAME)
                    .description("Count of decision plane policy evaluations by outcome")
                    .tag("action", action)
                    .tag("escalated", String.valueOf(resolution.escalated()))
                    .tag("fallback", String.valueOf(resolution.fallback()))
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
        }
    }

    /**
     * Records one bounded observation of a completed evaluation.
     *
     * <p>Deliberately takes only already-payload-free scalars: the lane, the versioned model id, whether
     * the engine reached an opinion, the answer confidence (if any), the measured latency, and the cost
     * (if the price schedule priced it). Nothing here can carry state, a credential, or a provider body.
     *
     * @param meterRegistry metric registry, or null
     * @param sample the bounded observation
     */
    public static void recordEvaluation(MeterRegistry meterRegistry, EvaluationSample sample) {
        Objects.requireNonNull(sample, "sample");

        String lane = laneTag(sample.lane());
        String model = traceAttribute(sample.modelId());
        String outcome = sample.opinion() ? "OPINION" : "NO_OPINION";
        String confidenceBucket = confidenceBucket(sample.confidence());
        String latencyBucket = latencyBucket(sample.latency());

        Span currentSpan = Span.current();
        if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
            currentSpan.setAttribute("decision.lane", lane);
            currentSpan.setAttribute("decision.model", model);
            currentSpan.setAttribute("decision.outcome", outcome);
            currentSpan.setAttribute("decision.confidence_bucket", confidenceBucket);
            currentSpan.setAttribute("decision.latency_bucket", latencyBucket);
        }

        if (meterRegistry == null) {
            return;
        }
        Counter.builder(EVALUATION_METRIC_NAME)
                .description("Count of decision evaluations by bounded outcome, lane and confidence bucket")
                .tag("lane", lane)
                .tag("outcome", outcome)
                .tag("confidence_bucket", confidenceBucket)
                .register(meterRegistry)
                .increment();
        Counter.builder(LATENCY_METRIC_NAME)
                .description("Count of decision evaluations by bounded latency bucket")
                .tag("lane", lane)
                .tag("latency_bucket", latencyBucket)
                .register(meterRegistry)
                .increment();
        if (sample.costUsd() != null) {
            Counter.builder(COST_METRIC_NAME)
                    .description("Cumulative decision evaluation cost in USD")
                    .tag("lane", lane)
                    .register(meterRegistry)
                    .increment(sample.costUsd());
        }
    }

    /**
     * The coarse bucket a confidence falls in, or {@link #UNKNOWN} when none was reported.
     *
     * <p>A bucket rather than the raw value: the raw confidence of a single decision is low-cardinality
     * only by accident, and a metric tag must be a closed set.
     *
     * @param confidence a confidence in {@code [0,1]}, or null
     * @return one of {@code UNKNOWN}, {@code <0.50}, {@code 0.50-0.75}, {@code 0.75-0.90},
     *     {@code 0.90-0.99}, {@code >=0.99}
     */
    public static String confidenceBucket(Double confidence) {
        if (confidence == null) {
            return "UNKNOWN";
        }
        double value = confidence;
        if (value < 0.50) {
            return "<0.50";
        }
        if (value < 0.75) {
            return "0.50-0.75";
        }
        if (value < 0.90) {
            return "0.75-0.90";
        }
        if (value < 0.99) {
            return "0.90-0.99";
        }
        return ">=0.99";
    }

    /**
     * The coarse bucket a latency falls in, or {@link #UNKNOWN} when none was reported.
     *
     * @param latency the measured latency, or null
     * @return one of {@code UNKNOWN}, {@code <50ms}, {@code 50-100ms}, {@code 100-250ms},
     *     {@code 250-500ms}, {@code 500ms-1s}, {@code >=1s}
     */
    public static String latencyBucket(Duration latency) {
        if (latency == null) {
            return "UNKNOWN";
        }
        long millis = latency.toMillis();
        if (millis < 50) {
            return "<50ms";
        }
        if (millis < 100) {
            return "50-100ms";
        }
        if (millis < 250) {
            return "100-250ms";
        }
        if (millis < 500) {
            return "250-500ms";
        }
        if (millis < 1000) {
            return "500ms-1s";
        }
        return ">=1s";
    }

    private static String laneTag(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return ProviderCategory.valueOf(value.trim().toUpperCase(Locale.ROOT))
                    .name();
        } catch (IllegalArgumentException _) {
            return UNKNOWN;
        }
    }

    private static String traceAttribute(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }

    /**
     * One bounded observation of a completed evaluation.
     *
     * @param lane the provider lane ({@code SELF_HOSTED}/{@code REMOTE}/{@code IN_PROCESS}), or null
     * @param modelId the versioned model identifier the provider reported, or null
     * @param opinion whether the engine reached an opinion
     * @param confidence the answer confidence, when the provider reported one
     * @param latency the measured end-to-end latency, or null
     * @param costUsd the priced cost of this decision, or null when it could not be priced
     */
    public record EvaluationSample(
            String lane, String modelId, boolean opinion, Double confidence, Duration latency, Double costUsd) {

        public EvaluationSample {
            if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
                throw new IllegalArgumentException("confidence must be within [0,1], got " + confidence);
            }
            if (latency != null && latency.isNegative()) {
                throw new IllegalArgumentException("latency must not be negative");
            }
            if (costUsd != null && costUsd < 0.0) {
                throw new IllegalArgumentException("costUsd must not be negative");
            }
        }
    }
}
