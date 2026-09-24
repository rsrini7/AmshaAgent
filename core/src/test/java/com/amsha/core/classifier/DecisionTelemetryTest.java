package com.amsha.core.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
