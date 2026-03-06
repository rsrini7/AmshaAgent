package com.arcclaw.core.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Registry to look up the persona-specific system prompt from Agent CDI beans.
 */
@ApplicationScoped
public class AgentPersonaRegistry {

    private final Instance<Agent> agents;
    private final Iterable<Agent> testAgents;

    protected AgentPersonaRegistry() {
        this.agents = null;
        this.testAgents = null;
    }

    @Inject
    public AgentPersonaRegistry(@Any Instance<Agent> agents) {
        this.agents = agents;
        this.testAgents = null;
    }

    /**
     * Package-private constructor for testing without CDI.
     */
    AgentPersonaRegistry(Iterable<Agent> agents) {
        this.agents = null;
        this.testAgents = agents;
    }

    public String getPersonaPrompt(AgentRoleEnum role) {
        Iterable<Agent> activeAgents = (testAgents != null) ? testAgents : agents;

        for (Agent agent : activeAgents) {
            if (agent.role().equals(role.toScopedName())) {
                return agent.systemPrompt();
            }
        }

        // Fallback: coordinator prompt if role not found
        for (Agent agent : activeAgents) {
            if (agent.role().equals(AgentRoleEnum.COORDINATOR.toScopedName())) {
                return agent.systemPrompt();
            }
        }


        return "You are ArcClaw, an AI agent. Be helpful and concise.";
    }
}
