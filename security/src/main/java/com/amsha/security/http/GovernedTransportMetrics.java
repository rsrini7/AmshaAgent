package com.amsha.security.http;

import jakarta.enterprise.inject.Instance;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.amsha.security.api.OutboundPurpose;

import io.micrometer.core.instrument.MeterRegistry;

public final class GovernedTransportMetrics {

    static final String REQUEST_TOTAL = "amsha.transport.requests.total";
    static final String REQUEST_DURATION = "amsha.transport.duration";

    private GovernedTransportMetrics() {}

    public static MeterRegistry resolve(Instance<MeterRegistry> instance) {
        if (instance != null && instance.isResolvable()) {
            return instance.get();
        }
        return null;
    }

    static void recordRequest(MeterRegistry registry, OutboundPurpose purpose, String status, long durationNanos) {
        if (registry == null) {
            return;
        }
        try {
            String safePurpose = resolvePurpose(purpose);
            String safeStatus = resolveStatus(status);

            registry.counter(REQUEST_TOTAL, "purpose", safePurpose, "status", safeStatus)
                    .increment();
            registry.timer(REQUEST_DURATION, "purpose", safePurpose, "status", safeStatus)
                    .record(durationNanos, TimeUnit.NANOSECONDS);
        } catch (Exception _) {
            // Metrics must never fail outbound transport operations
        }
    }

    static String resolvePurpose(OutboundPurpose purpose) {
        return purpose != null ? purpose.name().toLowerCase(Locale.ROOT) : "unknown";
    }

    static String resolveStatus(String status) {
        if (status == null || status.isBlank()) {
            return "unknown";
        }
        return status.trim().toLowerCase(Locale.ROOT);
    }
}
