package com.amsha.app.api;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;

final class DbConsoleMetrics {

    static final String QUERIES_TOTAL = "amsha.dbconsole.queries.total";
    static final String QUERY_DURATION = "amsha.dbconsole.query.duration";
    static final String ROWS_TOTAL = "amsha.dbconsole.rows.total";

    private DbConsoleMetrics() {}

    static void recordQuery(MeterRegistry registry, String backend, String status, long durationNanos, int rowCount) {
        if (registry == null) {
            return;
        }
        try {
            String safeBackend = resolveBackend(backend);
            String safeStatus = resolveStatus(status);

            registry.counter(QUERIES_TOTAL, "backend", safeBackend, "status", safeStatus)
                    .increment();
            registry.timer(QUERY_DURATION, "backend", safeBackend, "status", safeStatus)
                    .record(durationNanos, TimeUnit.NANOSECONDS);

            if (rowCount > 0) {
                registry.counter(ROWS_TOTAL, "backend", safeBackend).increment(rowCount);
            }
        } catch (Exception _) {
            // Metrics must never fail database console operations
        }
    }

    static String resolveBackend(String backend) {
        if (backend == null || backend.isBlank()) {
            return "unknown";
        }
        return backend.trim().toLowerCase(Locale.ROOT);
    }

    static String resolveStatus(String status) {
        if (status == null || status.isBlank()) {
            return "unknown";
        }
        return status.trim().toLowerCase(Locale.ROOT);
    }
}
