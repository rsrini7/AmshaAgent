package com.amsha.core.classifier;

import java.util.Objects;

import com.amsha.classifier.api.ToolRiskDecisionPolicy.PolicyResolution;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;

/**
 * Emits payload-free bounded metrics and trace attributes for decision-plane policy resolutions.
 *
 * <p>Never logs, records, or tags prompt, tool parameter, or state payloads.
 */
public final class DecisionTelemetry {

    public static final String METRIC_NAME = "amsha.decision.policy.resolutions";

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
}
