package com.amsha.core.agent.role;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

/**
 * Registry for dynamic tool-capability grants.
 * <p>
 * This allows tool extensions to register themselves and grant access to
 * specific roles at runtime without modifying the core {@link AgentRoleEnum}.
 */
@ApplicationScoped
public class CapabilityRegistry {

    private static final Logger log = Logger.getLogger(CapabilityRegistry.class);

    private final Map<String, Set<String>> roleSupplementalTools = new ConcurrentHashMap<>();

    /**
     * Grants a tool capability to a specific agent role.
     * <p>
     * Idempotent — duplicate grants log a warning instead of throwing.
     * This is safe for CDI hot-reload where {@code ToolDiscoveryService}
     * may re-register tools after {@link #clear()}.
     * <p>
     * Note: this method runs at CDI startup (called by ToolDiscoveryService),
     * before the database is fully initialized. It MUST NOT perform any
     * database writes or audit events.
     *
     * @param roleId   The canonical role ID (e.g., "researcher").
     * @param toolName The unique tool name (e.g., "browser_navigate").
     */
    public void grant(String roleId, String toolName) {
        Set<String> tools = roleSupplementalTools.computeIfAbsent(roleId, k -> ConcurrentHashMap.newKeySet());
        if (!tools.add(toolName)) {
            log.warnf("Idempotent grant ignored: role '%s' already has tool '%s'", roleId, toolName);
            return;
        }
        log.infof("Tool-capability granted: role=%s tool=%s", roleId, toolName);
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

    /**
     * Checks if a role has a specific tool capability.
     */
    public boolean hasCapability(String roleId, String toolName) {
        Set<String> tools = roleSupplementalTools.get(roleId);
        return tools != null && tools.contains(toolName);
    }

    /**
     * Clears all registered supplemental tools.
     * <p>
     * <strong>§4.2:</strong> This is idempotent and safe to call during
     * CDI hot-reload via {@code ToolDiscoveryService}.
     */
    public void clear() {
        roleSupplementalTools.clear();
    }
}
