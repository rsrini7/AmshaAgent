package com.amsha.core.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.OptionalDouble;

import com.amsha.classifier.api.ToolRiskAction;
import com.amsha.classifier.api.ToolRiskDecisionPolicy;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Tests for {@link DecisionTelemetry}.
 * Verifies that metrics and trace attributes are bounded and strictly payload-free.
 */
class DecisionTelemetryTest {

    @Test
    void recordsBoundedOutcomeMetricsWithoutPayloads() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        ToolRiskDecisionPolicy.PolicyResolution resolution = new ToolRiskDecisionPolicy.PolicyResolution(
                "tool.risk.bash", 2, ToolRiskAction.BLOCK, OptionalDouble.of(0.95), true, false, "HIGH_RISK_COMMAND");

        DecisionTelemetry.record(registry, resolution);

        Counter counter = registry.find(DecisionTelemetry.METRIC_NAME).counter();
        assertNotNull(counter, "Counter should be registered");
        assertEquals(1.0, counter.count());
        assertEquals("BLOCK", counter.getId().getTag("action"));
        assertEquals("true", counter.getId().getTag("escalated"));
        assertEquals("false", counter.getId().getTag("fallback"));
        assertEquals("BLOCK", counter.getId().getTag("outcome"));

        // Verify unbounded tags do NOT exist on the metric
        assertNull(counter.getId().getTag("decision_id"));
        assertNull(counter.getId().getTag("reason"));
        assertNull(counter.getId().getTag("schema_version"));

        // Verify no payload tags exist
        assertNull(counter.getId().getTag("payload"));
        assertNull(counter.getId().getTag("params"));
        assertNull(counter.getId().getTag("prompt"));
        assertNull(counter.getId().getTag("tool_name"));
    }

    @Test
    void handlesNullRegistrySafely() {
        ToolRiskDecisionPolicy.PolicyResolution resolution =
                new ToolRiskDecisionPolicy.PolicyResolution("dec.safe", 1, null, false, true, "LOW_CONFIDENCE");

        // Must not throw when registry is null
        DecisionTelemetry.record(null, resolution);
    }

    @Test
    void recordsFallbackOutcomeMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        ToolRiskDecisionPolicy.PolicyResolution fallback = new ToolRiskDecisionPolicy.PolicyResolution(
                "tool.risk.eval", 1, ToolRiskAction.REQUIRE_APPROVAL, true, true, "TIMEOUT");

        DecisionTelemetry.record(registry, fallback);

        Counter counter = registry.find(DecisionTelemetry.METRIC_NAME).counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
        assertEquals("REQUIRE_APPROVAL", counter.getId().getTag("action"));
        assertEquals("true", counter.getId().getTag("escalated"));
        assertEquals("true", counter.getId().getTag("fallback"));
        assertEquals("FALLBACK", counter.getId().getTag("outcome"));

        assertNull(counter.getId().getTag("decision_id"));
        assertNull(counter.getId().getTag("reason"));
    }

    @Test
    void recordsEvaluationMetricsWithBoundedLaneAndBucketTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        DecisionTelemetry.recordEvaluation(
                registry,
                new DecisionTelemetry.EvaluationSample(
                        "SELF_HOSTED", "jev-1.13.0", true, 0.91, Duration.ofMillis(12), 0.0004));

        Counter evaluations =
                registry.find(DecisionTelemetry.EVALUATION_METRIC_NAME).counter();
        assertNotNull(evaluations);
        assertEquals(1.0, evaluations.count());
        assertEquals("SELF_HOSTED", evaluations.getId().getTag("lane"));
        assertNull(evaluations.getId().getTag("model"));
        assertEquals("OPINION", evaluations.getId().getTag("outcome"));
        assertEquals("0.90-0.99", evaluations.getId().getTag("confidence_bucket"));

        Counter latency = registry.find(DecisionTelemetry.LATENCY_METRIC_NAME).counter();
        assertNotNull(latency);
        assertEquals("<50ms", latency.getId().getTag("latency_bucket"));
        assertNull(latency.getId().getTag("model"));

        Counter cost = registry.find(DecisionTelemetry.COST_METRIC_NAME).counter();
        assertNotNull(cost);
        assertEquals(0.0004, cost.count(), 1e-9);
        assertNull(cost.getId().getTag("model"));
    }

    @Test
    void modelAndUnknownLaneValuesDoNotCreateMetricSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionTelemetry.recordEvaluation(
                registry,
                new DecisionTelemetry.EvaluationSample(
                        "unregistered-lane-one", "model-one", true, 0.5, Duration.ofMillis(300), 0.01));
        DecisionTelemetry.recordEvaluation(
                registry,
                new DecisionTelemetry.EvaluationSample(
                        "unregistered-lane-two", "model-two", true, 0.5, Duration.ofMillis(300), 0.02));

        Counter evaluations =
                registry.find(DecisionTelemetry.EVALUATION_METRIC_NAME).counter();
        assertNotNull(evaluations);
        assertEquals(2.0, evaluations.count());
        assertEquals("unknown", evaluations.getId().getTag("lane"));
        assertNull(evaluations.getId().getTag("model"));
        assertEquals(
                1,
                registry.find(DecisionTelemetry.EVALUATION_METRIC_NAME).meters().size());
        assertEquals(
                1, registry.find(DecisionTelemetry.LATENCY_METRIC_NAME).meters().size());
        assertEquals(
                1, registry.find(DecisionTelemetry.COST_METRIC_NAME).meters().size());
    }

    @Test
    void anUnpricedEvaluationRecordsNoCostMeter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        DecisionTelemetry.recordEvaluation(
                registry,
                new DecisionTelemetry.EvaluationSample(
                        "REMOTE", "jev-latest", false, null, Duration.ofMillis(700), null));

        Counter evaluations =
                registry.find(DecisionTelemetry.EVALUATION_METRIC_NAME).counter();
        assertNotNull(evaluations);
        assertEquals("NO_OPINION", evaluations.getId().getTag("outcome"));
        // No confidence was reported, so the bucket is the explicit unknown sentinel rather than a value.
        assertEquals("UNKNOWN", evaluations.getId().getTag("confidence_bucket"));
        assertEquals(
                "500ms-1s",
                registry.find(DecisionTelemetry.LATENCY_METRIC_NAME)
                        .counter()
                        .getId()
                        .getTag("latency_bucket"));
        assertNull(registry.find(DecisionTelemetry.COST_METRIC_NAME).counter(), "an unknown cost is not zero");
    }

    @Test
    void absentLaneMapsToUnknownAndModelIsAbsentFromMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        DecisionTelemetry.recordEvaluation(
                registry, new DecisionTelemetry.EvaluationSample(null, "  ", true, 0.5, null, null));

        Counter evaluations =
                registry.find(DecisionTelemetry.EVALUATION_METRIC_NAME).counter();
        assertNotNull(evaluations);
        assertEquals("unknown", evaluations.getId().getTag("lane"));
        assertNull(evaluations.getId().getTag("model"));
        assertEquals("0.50-0.75", evaluations.getId().getTag("confidence_bucket"));
        assertEquals(
                "UNKNOWN",
                registry.find(DecisionTelemetry.LATENCY_METRIC_NAME)
                        .counter()
                        .getId()
                        .getTag("latency_bucket"));
    }

    @Test
    void handlesNullRegistryForEvaluationSamples() {
        DecisionTelemetry.recordEvaluation(
                null, new DecisionTelemetry.EvaluationSample("SELF_HOSTED", "m", true, 0.9, Duration.ofMillis(5), 0.1));
    }

    @Test
    void confidenceAndLatencyBucketsAreClosedSets() {
        assertEquals("UNKNOWN", DecisionTelemetry.confidenceBucket(null));
        assertEquals("<0.50", DecisionTelemetry.confidenceBucket(0.0));
        assertEquals("<0.50", DecisionTelemetry.confidenceBucket(0.499));
        assertEquals("0.50-0.75", DecisionTelemetry.confidenceBucket(0.5));
        assertEquals("0.75-0.90", DecisionTelemetry.confidenceBucket(0.75));
        assertEquals("0.90-0.99", DecisionTelemetry.confidenceBucket(0.9));
        assertEquals(">=0.99", DecisionTelemetry.confidenceBucket(1.0));

        assertEquals("UNKNOWN", DecisionTelemetry.latencyBucket(null));
        assertEquals("<50ms", DecisionTelemetry.latencyBucket(Duration.ofMillis(49)));
        assertEquals("50-100ms", DecisionTelemetry.latencyBucket(Duration.ofMillis(50)));
        assertEquals("100-250ms", DecisionTelemetry.latencyBucket(Duration.ofMillis(100)));
        assertEquals("250-500ms", DecisionTelemetry.latencyBucket(Duration.ofMillis(250)));
        assertEquals("500ms-1s", DecisionTelemetry.latencyBucket(Duration.ofMillis(500)));
        assertEquals(">=1s", DecisionTelemetry.latencyBucket(Duration.ofSeconds(1)));
    }

    @Test
    void refusesAnEvaluationSampleThatCannotBeBounded() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionTelemetry.EvaluationSample("L", "m", true, 1.5, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionTelemetry.EvaluationSample("L", "m", true, -0.1, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionTelemetry.EvaluationSample("L", "m", true, 0.5, Duration.ofMillis(-1), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionTelemetry.EvaluationSample("L", "m", true, 0.5, null, -0.01));
    }
}
