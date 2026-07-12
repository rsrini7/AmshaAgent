package com.amsha.core.pipeline;

import java.util.Optional;

import com.amsha.core.agent.result.AgentResult;

/** Result metadata produced by a processed turn, independent of presentation. */
public record TurnDispatchResult(
        Optional<String> sessionId,
        Optional<String> resolvedRole,
        Optional<AgentResult> agentResult,
        Optional<String> commandResponse) {

    public TurnDispatchResult {
        sessionId = sessionId == null ? Optional.empty() : sessionId.filter(value -> !value.isBlank());
        resolvedRole = resolvedRole == null ? Optional.empty() : resolvedRole.filter(value -> !value.isBlank());
        agentResult = agentResult == null ? Optional.empty() : agentResult;
        commandResponse = commandResponse == null ? Optional.empty() : commandResponse;
    }
}
