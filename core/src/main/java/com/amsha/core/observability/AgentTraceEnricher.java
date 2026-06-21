package com.amsha.core.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;

import org.jboss.logging.Logger;

import io.opentelemetry.api.trace.Span;

@ApplicationScoped
public class AgentTraceEnricher implements AgentObserver {

    private static final Logger log = Logger.getLogger(AgentTraceEnricher.class);

    @Inject
    AgentOtelContext otelContext;

    @Override
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
        // No-op: LLM token tracking handled by MetricsAgentObserver. This enricher only instruments spans.
    }

    @Override
    public void onStepComplete(String sessionId, String role, ReasoningStep step) {
        Span root = otelContext.getRootSpan(sessionId);
        if (root == null || !root.isRecording()) return;

        if (step.success() != null) {
            root.setAttribute("amsha.last.step.success", Boolean.TRUE.equals(step.success()));
        }
    }

    @Override
    public void onLoopComplete(String sessionId, String userId, String agentRole, String goalHash, AgentResult result) {
        Span root = otelContext.getRootSpan(sessionId);
        if (root == null || !root.isRecording()) return;

        root.setAttribute("amsha.loop.complete", true);
        if (result != null) {
            root.setAttribute("amsha.loop.outcome", result.getClass().getSimpleName());
        }

        log.debugf("[trace] session=%s role=%s complete", sessionId, agentRole);
    }
}
