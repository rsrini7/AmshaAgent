package com.arcclaw.core.agent.persona;

import java.util.Collections;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import org.jboss.logging.Logger;

/**
 * Registry to look up the persona-specific system prompt from Agent CDI beans.
 */
@ApplicationScoped
public class AgentPersonaRegistry {
    private static final Logger log = Logger.getLogger(AgentPersonaRegistry.class);

    private final Iterable<Agent> agents;

    protected AgentPersonaRegistry() {
        this.agents = Collections.emptyList();
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

        log.warnf("[AgentPersonaRegistry] No Agent bean registered for role '%s'. " +
                "Returning coordinator persona as fallback. " +
                "To fix: create a @ApplicationScoped class implementing Agent with role() = '%s'.",
                role.toScopedName(), role.toScopedName());

        // Fallback: coordinator prompt if role not found
        for (Agent agent : agents) {
            if (agent.role().equals(AgentRoleEnum.COORDINATOR.toScopedName())) {
                return agent.systemPrompt();
            }
        }

        return "You are ArcClaw, an AI agent. Be helpful and concise.";
    }
}

