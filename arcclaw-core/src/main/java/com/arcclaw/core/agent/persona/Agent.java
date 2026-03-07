package com.arcclaw.core.agent.persona;

import com.arcclaw.core.agent.loop.AgentLoop;

import java.util.List;

/**
 * Contract for all ArcClaw agent personas.
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
     */
    String systemPrompt();

    /**
     * List of tool names this agent is authorized to use.
     * <p>
     * The {@link AgentLoop} will reject any tool invocation not in
     * this list, even if the LLM requests it.
     *
     * @return Unmodifiable list of allowed tool names.
     */
    List<String> allowedTools();
}
