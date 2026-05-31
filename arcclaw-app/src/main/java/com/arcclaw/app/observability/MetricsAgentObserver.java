package com.arcclaw.app.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.observability.AgentObserver;

import io.micrometer.core.instrument.MeterRegistry;

@ApplicationScoped
public class MetricsAgentObserver implements AgentObserver {

    private static final String TAG_ROLE = "role";
    private static final String TAG_TOOL = "tool";
    private static final String UNKNOWN = "unknown";

    @Inject
    MeterRegistry registry;

    @Override
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
        registry.counter("arcclaw.agent.tokens", "type", "input").increment(inputTokens);
        registry.counter("arcclaw.agent.tokens", "type", "output").increment(outputTokens);
        // One counter per LLM round-trip
        registry.counter("arcclaw.agent.llm.calls").increment();
    }

    @Override
    @SuppressWarnings("java:S1192")
    public void onStepComplete(String sessionId, String role, ReasoningStep step) {
        // Count agent invocations tagged by role
        registry.counter("arcclaw.agent.invocations", TAG_ROLE, role != null ? role : UNKNOWN)
                .increment();

        // Track which tools are being called and how often
        if (step.action() != null
                && !ReasoningStep.FINISH.equals(step.action())
                && !ReasoningStep.PARALLEL_SENTINEL_DUNDER.equals(step.action())) {
            registry.counter(
                            "arcclaw.agent.tool.calls",
                            TAG_TOOL,
                            step.action(),
                            TAG_ROLE,
                            role != null ? role : UNKNOWN)
                    .increment();
        }

        if (ReasoningStep.PARALLEL_SENTINEL_DUNDER.equals(step.action())) {
            // Count batch-level metrics (invocation is a parallel batch)
            registry.counter("arcclaw.agent.parallel.batches", TAG_ROLE, role != null ? role : UNKNOWN)
                    .increment();
        }

        // Track finish steps separately - measures task completion rate
        if (ReasoningStep.FINISH.equals(step.action())) {
            registry.counter("arcclaw.agent.completions", "role", role != null ? role : "unknown")
                    .increment();
        }
    }
}
