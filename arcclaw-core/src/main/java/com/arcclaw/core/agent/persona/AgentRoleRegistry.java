package com.arcclaw.core.agent.persona;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.arcclaw.core.agent.role.AgentRoleEnum;

@ApplicationScoped
public class AgentRoleRegistry {

    private final Instance<Agent> agents;

    @Inject
    public AgentRoleRegistry(@Any Instance<Agent> agents) {
        this.agents = agents;
    }

    /** All registered roles - built-in + any third-party CDI beans. */
    public List<AgentRoleEnum> allRoles() {
        return agents.stream()
                .map(a -> AgentRoleEnum.fromString(a.role()))
                .distinct()
                .collect(Collectors.toUnmodifiableList());
    }

    /** Finds the Agent bean matching the given role id. */
    public Optional<Agent> resolve(AgentRoleEnum role) {
        return agents.stream().filter(a -> a.role().equals(role.toScopedName())).findFirst();
    }

    /** Convenience: resolve by raw string (used in IntentDetectorService). */
    public Optional<Agent> resolveByName(String roleName) {
        return resolve(AgentRoleEnum.fromString(roleName));
    }
}
