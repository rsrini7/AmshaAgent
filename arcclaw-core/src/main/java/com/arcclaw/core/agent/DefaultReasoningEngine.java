package com.arcclaw.core.agent;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;

/**
 * Temporary stub implementation to allow REST API testing
 * before full LangChain4J wiring in Phase 12.
 */
@ApplicationScoped
public class DefaultReasoningEngine implements ReasoningEngine {

    @Override
    public ReasoningStep nextStep(String goal, List<ReasoningStep> history) {
        return ReasoningStep.pending(
                "Stubbed thought",
                ReasoningStep.FINISH,
                Map.of("answer", "Stubbed answer"));
    }
}
