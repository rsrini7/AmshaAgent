package com.amsha.core.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.logging.Logger;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

/**
 * OTel session-level span management.
 * <p>
 * OpenTelemetry implementation for tracking agent root sessions and
 * emitting child spans with semantic conventions for token consumption.
 */
@ApplicationScoped
public class AgentOtelContext implements AgentObserver {

    private static final Logger log = Logger.getLogger(AgentOtelContext.class);

    @Inject
    Tracer tracer;

    private final ConcurrentHashMap<String, Span> rootSpans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Scope> rootScopes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> totalInputTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> totalOutputTokens = new ConcurrentHashMap<>();

    /**
     * Start a session-level root span.
     *
     * @param sessionId The workspace session ID.
     * @return The active root {@link Span}.
     */
    public Span startSessionSpan(String sessionId) {
        return startSessionSpan(sessionId, null, null);
    }

    public Span startSessionSpan(String sessionId, String userId, String agentRole) {
        if (sessionId == null) return null;
        var builder = tracer.spanBuilder(AgentOTelConstants.SPAN_SESSION)
                .setAttribute(AgentOTelConstants.ATTR_SESSION_ID, sessionId)
                .setAttribute(AgentOTelConstants.ATTR_OI_SPAN_KIND, AgentOTelConstants.OI_KIND_AGENT);
        if (userId != null) builder.setAttribute(AgentOTelConstants.ATTR_USER_ID, userId);
        if (agentRole != null) builder.setAttribute(AgentOTelConstants.ATTR_AGENT_ROLE, agentRole);
        Span span = builder.startSpan();
        rootSpans.put(sessionId, span);
        // §OTel Fix: Spans must be made current to be visible to child builders
        @SuppressWarnings("MustBeClosedChecker") // Scope stored in rootScopes, closed in endSessionSpan()
        Scope scope = span.makeCurrent();
        rootScopes.put(sessionId, scope);
        return span;
    }

    /**
     * End a session-level root span.
     *
     * @param sessionId The workspace session ID.
     */
    public void endSessionSpan(String sessionId) {
        if (sessionId == null) return;
        Scope scope = rootScopes.remove(sessionId);
        if (scope != null) {
            scope.close();
        }
        Span span = rootSpans.remove(sessionId);
        if (span != null) {
            span.end();
        }
        totalInputTokens.remove(sessionId);
        totalOutputTokens.remove(sessionId);
    }

    /**
     * Creates a child span under the session root span.
     *
     * @param sessionId     The workspace session ID.
     * @param operationName The span operation name (e.g., "tool.container_exec").
     * @return The child {@link Span}.
     */
    public Span childSpan(String sessionId, String operationName) {
        if (sessionId == null) return null;
        Span parent = rootSpans.get(sessionId);
        Context parentContext = parent != null ? Context.current().with(parent) : Context.current();
        return tracer.spanBuilder(operationName).setParent(parentContext).startSpan();
    }

    @Override
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
        if (sessionId == null) return;
        Span rootSpan = rootSpans.get(sessionId);
        if (rootSpan != null) {
            long totalIn = totalInputTokens
                    .computeIfAbsent(sessionId, _ -> new AtomicLong(0))
                    .addAndGet(inputTokens);
            long totalOut = totalOutputTokens
                    .computeIfAbsent(sessionId, _ -> new AtomicLong(0))
                    .addAndGet(outputTokens);
            rootSpan.setAttribute("llm.usage.prompt_tokens", totalIn);
            rootSpan.setAttribute("llm.usage.completion_tokens", totalOut);
        }
    }

    /**
     * Returns the root span for a session, or null if none exists.
     */
    public Span getRootSpan(String sessionId) {
        return sessionId != null ? rootSpans.get(sessionId) : null;
    }

    /**
     * Starts a child span for one agent reasoning iteration.
     * Must be ended via span.end() in a finally block by the caller.
     *
     * @return the started Span, or Span.getInvalid() if tracing is unavailable for
     *         this session
     */
    public Span startIterationSpan(String sessionId, int iteration, String agentRole) {
        Span parent = rootSpans.get(sessionId);
        if (parent == null) {
            log.debugf("No root span for session %s - returning no-op span", sessionId);
            return Span.getInvalid();
        }
        Context parentContext = Context.current().with(parent);
        return tracer.spanBuilder(AgentOTelConstants.SPAN_AGENT_ITERATION)
                .setParent(parentContext)
                .setAttribute(AgentOTelConstants.ATTR_SESSION_ID, sessionId)
                .setAttribute(AgentOTelConstants.ATTR_AGENT_ROLE, agentRole)
                .setAttribute(AgentOTelConstants.ATTR_ITERATION, (long) iteration)
                .startSpan();
    }

    /**
     * Starts a child span for a single tool invocation.
     * Must be ended via span.end() in a finally block by the caller.
     *
     * @return the started Span, or Span.getInvalid() if tracing is unavailable for
     *         this session
     */
    public Span startToolSpan(String sessionId, String toolName, String toolOperation) {
        Span parent = rootSpans.get(sessionId);
        if (parent == null) {
            log.debugf("No root span for session %s - returning no-op span", sessionId);
            return Span.getInvalid();
        }
        Context parentContext = Context.current().with(parent);
        return tracer.spanBuilder(AgentOTelConstants.SPAN_TOOL_CALL)
                .setParent(parentContext)
                .setAttribute(AgentOTelConstants.ATTR_SESSION_ID, sessionId)
                .setAttribute(AgentOTelConstants.ATTR_TOOL_NAME, toolName)
                .setAttribute(AgentOTelConstants.ATTR_TOOL_OPERATION, toolOperation)
                .setAttribute(AgentOTelConstants.ATTR_OI_SPAN_KIND, AgentOTelConstants.OI_KIND_TOOL)
                .startSpan();
    }
}
