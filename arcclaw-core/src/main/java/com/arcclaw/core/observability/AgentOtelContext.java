package com.arcclaw.core.observability;

import io.opentelemetry.api.trace.Span;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * OTel session-level span management.
 * <p>
 * Phase 9 will replace these stubs with real OpenTelemetry SDK span creation.
 * Currently returns no-op spans so that dependent classes compile and test
 * without the full observer layer.
 * <p>
 * <strong>Phase 7 refactor:</strong> Converted from static utility to
 * {@code @ApplicationScoped} bean for CDI injection into
 * {@code ProcessIsolationExecutor} and {@code AgentLoop}.
 */
@ApplicationScoped
public class AgentOtelContext {

    /**
     * Start a session-level root span.
     * <p>
     * Phase 9: will create a real root span for the entire agent session.
     *
     * @param sessionId The workspace session ID.
     * @return A no-op {@link Span} (stub).
     */
    public Span startSessionSpan(String sessionId) {
        // Phase 9: create real root span for the session
        return Span.getInvalid();
    }

    /**
     * End a session-level root span.
     * <p>
     * Phase 9: will close the root span and flush telemetry.
     *
     * @param sessionId The workspace session ID.
     */
    public void endSessionSpan(String sessionId) {
        // Phase 9: end the root span
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
    public Span childSpan(String sessionId, String operationName) {
        // Phase 9: will create real child span under the session root
        return Span.getInvalid();
    }
}
