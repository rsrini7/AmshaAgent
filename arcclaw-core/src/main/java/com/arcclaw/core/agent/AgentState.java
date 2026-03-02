package com.arcclaw.core.agent;

import java.util.List;

/**
 * Sealed interface representing the state of an agent execution.
 */
public sealed interface AgentState
        permits AgentState.InProgress,
                AgentState.AwaitingApproval,
                AgentState.Completed,
                AgentState.Failed,
                AgentState.Compromised {

    List<ReasoningStep> steps();

    record InProgress(List<ReasoningStep> steps) implements AgentState {}

    record AwaitingApproval(
            String toolName,
            String paramsJson,
            List<ReasoningStep> steps
    ) implements AgentState {
        public AgentResult toResult(String sessionId) {
            return AgentResult.awaitingApproval(toolName, paramsJson, sessionId, steps);
        }
    }

    record Completed(String answer, List<ReasoningStep> steps) implements AgentState {}

    record Failed(String reason, List<ReasoningStep> steps) implements AgentState {}

    record Compromised(String reason, List<ReasoningStep> steps) implements AgentState {}
}
