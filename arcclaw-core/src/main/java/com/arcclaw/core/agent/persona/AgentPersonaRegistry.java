package com.arcclaw.core.agent.persona;

import com.arcclaw.core.agent.role.AgentRoleEnum;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Registry to look up the persona-specific system prompt from Agent CDI beans.
 */
@ApplicationScoped
public class AgentPersonaRegistry {

    private final Iterable<Agent> agents;

    protected AgentPersonaRegistry() {
        this.agents = java.util.Collections.emptyList();
    }

    @Inject
    public AgentPersonaRegistry(@Any Instance<Agent> agents) {
        this.agents = agents;
    }

    /**
     * Package-private constructor for testing without CDI.
     */
    AgentPersonaRegistry(Iterable<Agent> agents) {
        this.agents = agents;
    }

    public String getPersonaPrompt(AgentRoleEnum role) {
        if (agents == null) {
            return "You are ArcClaw, an AI agent. Be helpful and concise.";
        }

        for (Agent agent : agents) {
            if (agent.role().equals(role.toScopedName())) {
                return agent.systemPrompt();
            }
        }

        // Fallback: coordinator prompt if role not found
        for (Agent agent : agents) {
            if (agent.role().equals(AgentRoleEnum.COORDINATOR.toScopedName())) {
                return agent.systemPrompt();
            }
        }

        return "You are ArcClaw, an AI agent. Be helpful and concise.";
    }
}

