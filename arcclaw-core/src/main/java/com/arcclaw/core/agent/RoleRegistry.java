package com.arcclaw.core.agent;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class RoleRegistry {

    /**
     * @param role the agent role
     * @return the default maximum iterations for the given role
     */
    public int defaultMaxIterations(AgentRoleEnum role) {
        if (role == null) {
            return 5;
        }
        return role.definition().maxIterations();
    }

    /**
     * @param role the agent role
     * @return the set of permitted tools for the given role
     */
    public java.util.Set<String> permittedTools(AgentRoleEnum role) {
        if (role == null) return java.util.Set.of();
        return role.definition().toolNames();
    }

    /**
     * Controls which roles inherently require human approval before their tools execute.
     * 
     * @param role the agent role
     * @return true if the role's tool calls require explicit approval
     */
    public boolean requiresApprovalForTools(AgentRoleEnum role) {
        if (role == null) return true;
        return role.definition().requiresApproval();
    }
}
