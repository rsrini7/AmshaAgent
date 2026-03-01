package com.arcclaw.core.agent;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

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
        return switch (role) {
            case ARCHITECT, COORDINATOR -> 10;
            case RESEARCHER -> 15;
            case CODER, DEBUGGER -> 5;
        };
    }

    /**
     * @param role the agent role
     * @return the list of permitted tools for the given role
     */
    public List<String> permittedTools(AgentRoleEnum role) {
        if (role == null) return List.of();
        
        return switch (role) {
            case RESEARCHER, COORDINATOR -> List.of("github", "memory_search", "scheduletask", "manageschedule");
            case ARCHITECT, CODER, DEBUGGER -> List.of();
        };
    }

    /**
     * Controls which roles inherently require human approval before their tools execute.
     * 
     * @param role the agent role
     * @return true if the role's tool calls require explicit approval
     */
    public boolean requiresApprovalForTools(AgentRoleEnum role) {
        if (role == null) return true;

        return switch (role) {
            case COORDINATOR, RESEARCHER -> true; // By default these use 'github'
            case ARCHITECT, CODER, DEBUGGER -> false; // No tools anyway, but strict if they gain them
        };
    }
}
