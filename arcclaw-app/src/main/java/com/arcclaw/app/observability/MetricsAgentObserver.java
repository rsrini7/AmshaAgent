package com.arcclaw.app.observability;

import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.agent.ReasoningStep;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MetricsAgentObserver implements AgentObserver {

    @Inject
    MeterRegistry registry;

    @Override
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
        registry.counter("arcclaw.agent.tokens",
                "type", "input").increment(inputTokens);
        registry.counter("arcclaw.agent.tokens",
                "type", "output").increment(outputTokens);
    }

    @Override
    public void onStepComplete(String sessionId, String role, ReasoningStep step) {
        // Count agent invocations tagged by role
        registry.counter("arcclaw.agent.invocations",
                "role", role != null ? role : "unknown").increment();
    }
}
