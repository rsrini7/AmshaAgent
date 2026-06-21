package com.amsha.core.agent.role;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import io.quarkus.qute.TemplateData;

/**
 * Immutable definition of an agent's role attributes, operational constraints,
 * and permitted tool capabilities.
 * <p>
 * This record standardizes role capabilities so that execution boundaries
 * (like permitted tools) are consistent between the LLM prompt, the agent
 * runtime, and the governance layer.
 *
 * @param canonicalName     The unique system identifier for the role (e.g., "coordinator").
 * @param displayName       The human-readable title (e.g., "Vishnu (Coordinator)").
 * @param intentDescription A high-level description of what the role does. Defaults to "" if null.
 * @param maxIterations     The maximum number of reactive loop iterations allowed before termination.
 * @param requiresApproval  If true, all tool executions by this role require explicit HITL approval.
 * @param capabilities      The set of Tools this role is allowed to invoke.
 */
@TemplateData
public record RoleDefinition(
        String canonicalName,
        String displayName,
        String intentDescription,
        int maxIterations,
        boolean requiresApproval,
        boolean codeCapable,
        Set<ToolCapability> capabilities,
        Set<String> aliases) {
    /**
     * Compact constructor enforcing non-null/non-blank rules and deep immutability.
     */
    public RoleDefinition {
        if (canonicalName == null || canonicalName.isBlank()) {
            throw new IllegalArgumentException("canonicalName must not be null or blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be null or blank");
        }
        if (intentDescription == null) {
            intentDescription = "";
        }
        capabilities = (capabilities == null) ? Set.of() : Set.copyOf(capabilities);
        aliases = (aliases == null) ? Set.of() : Set.copyOf(aliases);
    }

    /**
     * Returns the canonical string names of all tools permitted by this role.
     *
     * @return An immutable set of tool name strings. Guaranteed never null.
     */
    public Set<String> toolNames() {
        return capabilities.stream().map(ToolCapability::toolName).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Helper for unit testing and dynamic grants. Returns a copy with additional capabilities.
     */
    public RoleDefinition withCapabilities(Set<ToolCapability> additional) {
        Set<ToolCapability> all = new HashSet<>(this.capabilities);
        all.addAll(additional);
        return new RoleDefinition(
                canonicalName,
                displayName,
                intentDescription,
                maxIterations,
                requiresApproval,
                codeCapable,
                all,
                aliases);
    }

    /** Returns true if this role is permitted to generate or fix source code blocks. */
    public boolean canGenerateCode() {
        return codeCapable;
    }
}
