package com.arcclaw.core.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.ConcurrentHashMap;

/**
 * OTel session-level span management.
 * <p>
 * Phase 9 OpenTelemetry implementation for tracking agent root sessions and
 * emitting child spans with semantic conventions for token consumption.
 */
@ApplicationScoped
public class AgentOtelContext implements AgentObserver {

    @Inject
    Tracer tracer;

    private final ConcurrentHashMap<String, Span> rootSpans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Scope> rootScopes = new ConcurrentHashMap<>();

    /**
     * Start a session-level root span.
     *
     * @param sessionId The workspace session ID.
     * @return The active root {@link Span}.
     */
    public Span startSessionSpan(String sessionId) {
        Span span = tracer.spanBuilder("arcclaw.session")
                .setAttribute("sessionId", sessionId)
                .startSpan();
        rootSpans.put(sessionId, span);
        rootScopes.put(sessionId, span.makeCurrent());
        return span;
    }

    /**
     * End a session-level root span.
     *
     * @param sessionId The workspace session ID.
     */
    public void endSessionSpan(String sessionId) {
        Span span = rootSpans.remove(sessionId);
        if (span != null) {
            span.end();
        }
        Scope scope = rootScopes.remove(sessionId);
        if (scope != null) {
            scope.close();
        }
    }

    /**
     * Creates a child span under the session root span.
     *
     * @param sessionId     The workspace session ID.
     * @param operationName The span operation name (e.g., "tool.container_exec").
     * @return The child {@link Span}.
     */
    public Span childSpan(String sessionId, String operationName) {
        return tracer.spanBuilder(operationName).startSpan();
    }

    @Override
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
        Span rootSpan = rootSpans.get(sessionId);
        if (rootSpan != null) {
            rootSpan.setAttribute("llm.usage.prompt_tokens", inputTokens);
            rootSpan.setAttribute("llm.usage.completion_tokens", outputTokens);
        }
    }
}
