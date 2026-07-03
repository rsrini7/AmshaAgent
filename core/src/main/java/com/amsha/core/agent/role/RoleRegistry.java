package com.amsha.core.agent.role;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class RoleRegistry {

    @Inject
    CapabilityRegistry capabilityRegistry;

    private final ConcurrentHashMap<String, RoleDefinition> dynamicRoles = new ConcurrentHashMap<>();

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
    public Set<String> permittedTools(AgentRoleEnum role) {
        if (role == null) return Set.of();
        Set<String> coreTools = role.definition().toolNames();

        // Null-safe for unit testing
        if (capabilityRegistry == null) {
            return coreTools;
        }

        Set<String> supplemental = capabilityRegistry.getSupplementalTools(role.id());

        if (supplemental == null || supplemental.isEmpty()) {
            return coreTools;
        }

        Set<String> all = new HashSet<>(coreTools);
        all.addAll(supplemental);
        return Collections.unmodifiableSet(all);
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

    /**
     * Controls which roles require human approval by evaluating the raw role name.
     * Checks dynamic skill roles first to respect custom requiresApproval definitions,
     * falling back to static role configuration.
     *
     * @param roleName the raw role name string
     * @return true if the role's tool calls require explicit approval
     */
    public boolean requiresApprovalForTools(String roleName) {
        if (roleName == null) return true;
        RoleDefinition dynamicDef = dynamicRoles.get(roleName);
        if (dynamicDef != null) {
            return dynamicDef.requiresApproval();
        }
        try {
            AgentRoleEnum role = AgentRoleEnum.fromString(roleName);
            return role.definition().requiresApproval();
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /**
     * Registers a new dynamic role derived from a skill.
     * @param name The canonical skill name
     * @param definition The compiled role definition
     */
    public void registerDynamic(String name, RoleDefinition definition) {
        dynamicRoles.put(AgentRoleEnum.SKILL_PREFIX + name, definition);
    }

    /**
     * @return All currently registered dynamic roles.
     */
    public Collection<RoleDefinition> getDynamicRoles() {
        return Collections.unmodifiableCollection(dynamicRoles.values());
    }

    /**
     * Resolves permitted tools for a dynamic role.
     * @param canonicalName The full canonical name (e.g. "skill:code-reviewer")
     * @return The immutable set of tool names, or empty if unknown.
     */
    public Set<String> permittedToolsForDynamic(String canonicalName) {
        RoleDefinition def = dynamicRoles.get(canonicalName);
        return def == null
                ? Set.of()
                : def.capabilities().stream().map(ToolCapability::toolName).collect(Collectors.toUnmodifiableSet());
    }
}
