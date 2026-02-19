package com.arcclaw.core.observability;

import io.opentelemetry.api.trace.Span;

/**
 * Stub for OTel session-level span management.
 * <p>
 * Full implementation in Phase 9. Currently returns no-op spans
 * so that {@code ProcessIsolationExecutor} compiles without the
 * full observer layer.
 */
public final class AgentOtelContext {

    private AgentOtelContext() {
    }

    /**
     * Creates a child span under the session root span.
     * <p>
     * Phase 9 will replace this with real span creation using
     * the OpenTelemetry SDK and session-root span tracking.
     *
     * @param sessionId     The workspace session ID.
     * @param operationName The span operation name (e.g., "tool.container_exec").
     * @return A no-op {@link Span} (stub).
     */
    public static Span childSpan(String sessionId, String operationName) {
        // Phase 9: will create real child span under the session root
        return Span.getInvalid();
    }
}
