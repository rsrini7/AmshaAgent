package com.arcclaw.core.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import io.opentelemetry.api.trace.Span;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AgentTraceEnricher implements AgentObserver {

    private static final Logger log = Logger.getLogger(AgentTraceEnricher.class);

    @Inject
    AgentOtelContext otelContext;

    @Override
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
    }

    @Override
    public void onStepComplete(String sessionId, String role, ReasoningStep step) {
        Span root = otelContext.getRootSpan(sessionId);
        if (root == null || !root.isRecording()) return;

        if (step.success() != null) {
            root.setAttribute("arcclaw.last.step.success", Boolean.TRUE.equals(step.success()));
        }
    }

    @Override
    public void onLoopComplete(String sessionId, String userId, String agentRole, String goalHash, AgentResult result) {
        Span root = otelContext.getRootSpan(sessionId);
        if (root == null || !root.isRecording()) return;

        root.setAttribute("arcclaw.loop.complete", true);
        if (result != null) {
            root.setAttribute("arcclaw.loop.outcome", result.getClass().getSimpleName());
        }

        log.debugf("[trace] session=%s role=%s complete", sessionId, agentRole);
    }
}
