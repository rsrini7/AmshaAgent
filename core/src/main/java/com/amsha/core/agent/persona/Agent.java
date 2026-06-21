package com.amsha.core.agent.persona;

import java.util.List;

import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;

/**
 * Contract for all Amsha Agent agent personas.
 * <p>
 * Each agent has a fixed role, a hardcoded system prompt that defines
 * strict behavioral boundaries, and a list of tools it is authorized
 * to use. The {@link AgentLoop} enforces these boundaries.
 */
public interface Agent {

    /**
     * Unique role identifier matching {@link AgentRole#value()}.
     */
    String role();

    /**
     * System prompt defining the agent's personality, capabilities,
     * and strict behavioral boundaries.
     * <p>
     * This prompt is injected into the LLM context at the start of
     * every reasoning loop to enforce persona invariants.
     *
     * @param context the execution context containing dynamic metadata (e.g. scheduling)
     */
    String systemPrompt(AgentExecutionContext context);

    /**
     * List of tool names this agent is authorized to use.
     * <p>
     * The {@link AgentLoop} will reject any tool invocation not in
     * this list, even if the LLM requests it.
     *
     * @return Unmodifiable list of allowed tool names.
     */
    List<String> allowedTools();

    /**
     * Synthesizes multi-agent findings into a final resolution.
     * Overridden by specialist synthesizer agents (e.g. Dattatreya).
     */
    default AgentResult synthesize(String sessionId, String combinedContext, List<ReasoningStep> history) {
        throw new UnsupportedOperationException("This agent role does not support multi-agent synthesis");
    }
}
