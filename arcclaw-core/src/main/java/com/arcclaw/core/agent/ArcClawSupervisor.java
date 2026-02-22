package com.arcclaw.core.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * Top-level supervisor that coordinates the agent persona roster.
 * <p>
 * Discovers all {@link Agent} implementations via CDI {@code Instance<Agent>}
 * and provides lookup, listing, and delegation capabilities.
 * <p>
 * In future phases, this will orchestrate multi-agent conversations
 * where Vishnu delegates sub-tasks to specialized agents.
 */
@ApplicationScoped
public class ArcClawSupervisor {

    private static final Logger log = Logger.getLogger(ArcClawSupervisor.class);

    @Inject
    Instance<Agent> agents;

    @Inject
    AgentLoop agentLoop;

    /**
     * List all discovered agent roles.
     */
    public List<String> listAgentRoles() {
        return StreamSupport.stream(agents.spliterator(), false)
                .map(Agent::role)
                .toList();
    }

    /**
     * Find an agent by its role identifier.
     */
    public Optional<Agent> findAgent(String role) {
        return StreamSupport.stream(agents.spliterator(), false)
                .filter(a -> a.role().equals(role))
                .findFirst();
    }

    /**
     * Execute a goal using a specific agent persona with the given engine.
     *
     * @param sessionId The chat-bot sesstion id
     * @param role   The agent role to use.
     * @param goal   The task goal.
     * @param engine The reasoning engine (LLM abstraction).
     * @return The agent result from the reasoning loop.
     * @throws IllegalArgumentException if the agent role is not found.
     */
    public AgentResult executeWithAgent(String sessionId, String role, String goal, ReasoningEngine engine) {
        var agent = findAgent(role)
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent role: " + role));
                
        log.infof("Supervisor delegating to '%s': %s", agent.role(),
                goal.length() > 100 ? goal.substring(0, 100) + "..." : goal);
                
        // USE the correct 5-parameter AgentLoop signature
        return agentLoop.run(sessionId, agent.role(), goal, engine, 5);
    }
}
