package com.arcclaw.core.agent.role;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Registry for dynamic tool-capability grants.
 * <p>
 * This allows tool extensions to register themselves and grant access to
 * specific roles at runtime without modifying the core {@link AgentRoleEnum}.
 */
@ApplicationScoped
public class CapabilityRegistry {

    private final Map<String, Set<String>> roleSupplementalTools = new ConcurrentHashMap<>();

    /**
     * Grants a tool capability to a specific agent role.
     *
     * @param roleId   The canonical role ID (e.g., "researcher").
     * @param toolName The unique tool name (e.g., "browser_navigate").
     */
    public void grant(String roleId, String toolName) {
        roleSupplementalTools.computeIfAbsent(roleId, k -> ConcurrentHashMap.newKeySet()).add(toolName);
    }

    /**
     * Returns the set of supplemental tool names granted to a role.
     *
     * @param roleId The canonical role ID.
     * @return An unmodifiable set of tool name strings.
     */
    public Set<String> getSupplementalTools(String roleId) {
        Set<String> tools = roleSupplementalTools.get(roleId);
        return (tools == null) ? Set.of() : Collections.unmodifiableSet(tools);
    }
}
