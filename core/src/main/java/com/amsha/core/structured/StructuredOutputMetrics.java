package com.amsha.core.structured;

import io.micrometer.core.instrument.MeterRegistry;

/** Payload-free metrics for structured-output capability and fallback behavior. */
public final class StructuredOutputMetrics {

    public static final String SCHEMA_REJECTION_METRIC = "amsha.llm.structured.schema.rejection";

    private StructuredOutputMetrics() {}

    /**
     * Records a provider schema rejection without allowing metrics failures to change fallback behavior.
     *
     * @param meterRegistry registry, or {@code null} when metrics are unavailable
     * @param operation bounded operation identifier
     */
    public static void recordSchemaRejection(MeterRegistry meterRegistry, String operation) {
        if (meterRegistry == null || operation == null || operation.isBlank()) {
            return;
        }
        try {
            meterRegistry
                    .counter(SCHEMA_REJECTION_METRIC, "operation", operation)
                    .increment();
        } catch (RuntimeException _) {
            // Metrics are observational; schema fallback must remain deterministic if the registry is unavailable.
        }
    }
}
