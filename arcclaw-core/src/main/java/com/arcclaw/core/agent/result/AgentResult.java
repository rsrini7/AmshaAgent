package com.arcclaw.core.agent.result;

import java.util.List;

import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import io.quarkus.qute.TemplateData;

/**
 * Result of an {@link AgentLoop} execution.
 */
public sealed interface AgentResult
        permits AgentResult.Finished,
                AgentResult.StructuredFinished,
                AgentResult.AwaitingApproval,
                AgentResult.LoopExceeded,
                AgentResult.Failed {

    List<ReasoningStep> steps();
    int totalInputTokens();
    int totalOutputTokens();

    enum FailureCategory {
        COMPLIANCE_VIOLATION,
        SYSTEM_ERROR,
        BUDGET_EXCEEDED,
        SCHEMA_ERROR,
        USER_DENIED,
        RATE_LIMIT
    }

    @TemplateData
    record Finished(
            String answer,
            List<ReasoningStep> steps,
            int totalInputTokens,
            int totalOutputTokens
    ) implements AgentResult {}

    @TemplateData
    record StructuredFinished(
            String answer,
            Object structured,
            List<ReasoningStep> steps,
            int totalInputTokens,
            int totalOutputTokens
    ) implements AgentResult {}

    @TemplateData
    record AwaitingApproval(
            String toolName,
            String paramsJson,
            String sessionId,
            List<ReasoningStep> steps,
            int totalInputTokens,
            int totalOutputTokens
    ) implements AgentResult {}

    @TemplateData
    record LoopExceeded(
            int maxIterations,
            List<ReasoningStep> steps,
            int totalInputTokens,
            int totalOutputTokens
    ) implements AgentResult {}

    @TemplateData
    record Failed(
            String reason,
            FailureCategory category,
            List<ReasoningStep> steps,
            int totalInputTokens,
            int totalOutputTokens
    ) implements AgentResult {}

    // Static factories mapping to the new types

    static AgentResult finished(String answer, List<ReasoningStep> steps) {
        return new Finished(answer, steps, sumIn(steps), sumOut(steps));
    }

    static AgentResult structuredFinished(String answer, Object structured, List<ReasoningStep> steps) {
        return new StructuredFinished(answer, structured, steps, sumIn(steps), sumOut(steps));
    }

    static AgentResult awaitingApproval(String toolName, String paramsJson, String sessionId, List<ReasoningStep> steps) {
        return new AwaitingApproval(toolName, paramsJson, sessionId, steps, sumIn(steps), sumOut(steps));
    }

    static AgentResult loopExceeded(int maxIterations, List<ReasoningStep> steps) {
        return new LoopExceeded(maxIterations, steps, sumIn(steps), sumOut(steps));
    }

    static AgentResult failed(String reason, FailureCategory category, List<ReasoningStep> steps) {
        return new Failed(reason, category, steps, sumIn(steps), sumOut(steps));
    }

    private static int sumIn(List<ReasoningStep> steps) {
        if (steps == null) return 0;
        return steps.stream().mapToInt(ReasoningStep::inputTokens).sum();
    }

    private static int sumOut(List<ReasoningStep> steps) {
        if (steps == null) return 0;
        return steps.stream().mapToInt(ReasoningStep::outputTokens).sum();
    }
}
