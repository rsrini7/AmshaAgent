package com.arcclaw.app.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.observability.AgentObserver;
import io.micrometer.core.instrument.MeterRegistry;

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
        // One counter per LLM round-trip
        registry.counter("arcclaw.agent.llm.calls").increment();
    }

    @Override
    public void onStepComplete(String sessionId, String role, ReasoningStep step) {
        // Count agent invocations tagged by role
        registry.counter("arcclaw.agent.invocations",
                "role", role != null ? role : "unknown").increment();

        // Track which tools are being called and how often
        if (step.action() != null 
                && !ReasoningStep.FINISH.equals(step.action())
                && !ReasoningStep.PARALLEL_SENTINEL_DUNDER.equals(step.action())) {
            registry.counter("arcclaw.agent.tool.calls",
                    "tool", step.action(),
                    "role", role != null ? role : "unknown").increment();
        }

        if (ReasoningStep.PARALLEL_SENTINEL_DUNDER.equals(step.action())) {
            // Count batch-level metrics (invocation is a parallel batch)
            registry.counter("arcclaw.agent.parallel.batches",
                    "role", role != null ? role : "unknown").increment();
        }

        // Track finish steps separately — measures task completion rate
        if (ReasoningStep.FINISH.equals(step.action())) {
            registry.counter("arcclaw.agent.completions",
                    "role", role != null ? role : "unknown").increment();
        }                
    }
}
