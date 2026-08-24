package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.tool.api.ToolCapability;

/**
 * Resolves the persona that should execute a tool when the current persona is
 * not authorized to use it.
 *
 * <p>Tool grants remain the source of authorization. This resolver only
 * selects a delegation target; it never grants a capability and never
 * bypasses {@link com.amsha.core.agent.loop.ToolInvocationGuard}.</p>
 */
@ApplicationScoped
public final class ToolDelegationResolver {
    private static final String COORDINATOR = AgentRoleEnum.COORDINATOR_ID;
    private static final String RESEARCHER = AgentRoleEnum.RESEARCHER_ID;
    private static final String CODER = AgentRoleEnum.CODER_ID;
    private static final List<String> FALLBACK_OWNER_PRIORITY = List.of(
            COORDINATOR,
            RESEARCHER,
            CODER,
            AgentRoleEnum.DEBUGGER_ID,
            AgentRoleEnum.ARCHITECT_ID,
            AgentRoleEnum.SYNTHESIZER_ID,
            AgentRoleEnum.NURTURER_ID,
            AgentRoleEnum.OPTIMIZER_ID,
            AgentRoleEnum.SENTINEL_ID,
            AgentRoleEnum.HEALER_ID,
            AgentRoleEnum.PROTECTOR_ID,
            AgentRoleEnum.PURIFIER_ID);

    private static final Set<String> ALL_BUILT_IN_ROLES =
            AgentRoleEnum.values().stream().map(AgentRoleEnum::id).collect(Collectors.toUnmodifiableSet());

    private static final Set<String> MEMORY_ROLES = Set.of(
            AgentRoleEnum.ARCHITECT_ID,
            AgentRoleEnum.COORDINATOR_ID,
            AgentRoleEnum.RESEARCHER_ID,
            AgentRoleEnum.SYNTHESIZER_ID,
            AgentRoleEnum.CODER_ID);

    private static final Set<String> RECALL_MEMORY_ROLES = Set.of(
            AgentRoleEnum.ARCHITECT_ID,
            AgentRoleEnum.COORDINATOR_ID,
            AgentRoleEnum.RESEARCHER_ID,
            AgentRoleEnum.SYNTHESIZER_ID,
            AgentRoleEnum.CODER_ID,
            AgentRoleEnum.DEBUGGER_ID);

    private static final Map<String, String> EXPLICIT_OWNERS = Map.ofEntries(
            Map.entry(ToolCapability.GITHUB.toolName(), RESEARCHER),
            Map.entry(ToolCapability.SCHEDULE_TASK.toolName(), COORDINATOR),
            Map.entry(ToolCapability.MANAGE_SCHEDULE.toolName(), COORDINATOR),
            Map.entry(ToolCapability.RECALL_MEMORY_SEARCH.toolName(), COORDINATOR),
            Map.entry(ToolCapability.ARCHIVAL_MEMORY_SEARCH.toolName(), COORDINATOR),
            Map.entry(ToolCapability.CORE_MEMORY_READ.toolName(), COORDINATOR),
            Map.entry(ToolCapability.CORE_MEMORY_APPEND.toolName(), COORDINATOR),
            Map.entry(ToolCapability.CORE_MEMORY_REPLACE.toolName(), COORDINATOR),
            Map.entry(ToolCapability.ARCHIVAL_MEMORY_INSERT.toolName(), COORDINATOR),
            Map.entry(ToolCapability.BROWSER_NAVIGATE.toolName(), RESEARCHER),
            Map.entry(ToolCapability.SANDBOX_EXEC.toolName(), CODER),
            Map.entry(ToolCapability.INGEST_DOCUMENT.toolName(), COORDINATOR),
            Map.entry(ToolCapability.MANAGE_CHECKLIST.toolName(), CODER),
            Map.entry(ToolCapability.REGISTER_MCP_SERVER.toolName(), CODER),
            Map.entry(ToolCapability.WEB_SEARCH_BRAVE.toolName(), RESEARCHER),
            Map.entry(ToolCapability.WEB_SEARCH_SERPER.toolName(), RESEARCHER),
            Map.entry(ToolCapability.AGENT_TASK.toolName(), COORDINATOR),
            Map.entry(ToolCapability.WEB_FETCH.toolName(), RESEARCHER),
            Map.entry(ToolCapability.SELF_EVOLVE_REFLECT.toolName(), COORDINATOR));

    private static final Map<String, Set<String>> EXPLICIT_GRANTS = Map.ofEntries(
            Map.entry(
                    ToolCapability.GITHUB.toolName(),
                    Set.of(
                            AgentRoleEnum.ARCHITECT_ID,
                            COORDINATOR,
                            RESEARCHER,
                            AgentRoleEnum.DEBUGGER_ID,
                            AgentRoleEnum.SYNTHESIZER_ID)),
            Map.entry(ToolCapability.SCHEDULE_TASK.toolName(), Set.of(COORDINATOR, AgentRoleEnum.SYNTHESIZER_ID)),
            Map.entry(ToolCapability.MANAGE_SCHEDULE.toolName(), Set.of(COORDINATOR, AgentRoleEnum.SYNTHESIZER_ID)),
            Map.entry(ToolCapability.RECALL_MEMORY_SEARCH.toolName(), RECALL_MEMORY_ROLES),
            Map.entry(ToolCapability.ARCHIVAL_MEMORY_SEARCH.toolName(), MEMORY_ROLES),
            Map.entry(ToolCapability.CORE_MEMORY_READ.toolName(), MEMORY_ROLES),
            Map.entry(ToolCapability.CORE_MEMORY_APPEND.toolName(), MEMORY_ROLES),
            Map.entry(ToolCapability.CORE_MEMORY_REPLACE.toolName(), MEMORY_ROLES),
            Map.entry(ToolCapability.ARCHIVAL_MEMORY_INSERT.toolName(), MEMORY_ROLES),
            Map.entry(ToolCapability.BROWSER_NAVIGATE.toolName(), Set.of(RESEARCHER)),
            Map.entry(ToolCapability.SANDBOX_EXEC.toolName(), Set.of(CODER, AgentRoleEnum.DEBUGGER_ID)),
            Map.entry(
                    ToolCapability.INGEST_DOCUMENT.toolName(),
                    Set.of(COORDINATOR, AgentRoleEnum.SYNTHESIZER_ID, AgentRoleEnum.NURTURER_ID)),
            Map.entry(ToolCapability.MANAGE_CHECKLIST.toolName(), Set.of(CODER, AgentRoleEnum.DEBUGGER_ID)),
            Map.entry(ToolCapability.REGISTER_MCP_SERVER.toolName(), Set.of(CODER, AgentRoleEnum.DEBUGGER_ID)),
            Map.entry(ToolCapability.WEB_SEARCH_BRAVE.toolName(), Set.of(RESEARCHER, AgentRoleEnum.SYNTHESIZER_ID)),
            Map.entry(ToolCapability.WEB_SEARCH_SERPER.toolName(), Set.of(RESEARCHER, AgentRoleEnum.SYNTHESIZER_ID)),
            Map.entry(
                    ToolCapability.AGENT_TASK.toolName(),
                    Set.of(COORDINATOR, AgentRoleEnum.SYNTHESIZER_ID, RESEARCHER)),
            Map.entry(
                    ToolCapability.WEB_FETCH.toolName(), Set.of(RESEARCHER, AgentRoleEnum.SYNTHESIZER_ID, COORDINATOR)),
            Map.entry(ToolCapability.SELF_EVOLVE_REFLECT.toolName(), Set.of(COORDINATOR)));

    private final CapabilityRegistry capabilityRegistry;

    @Inject
    public ToolDelegationResolver(CapabilityRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
    }

    /** Constructor for pure unit tests and non-CDI callers. */
    ToolDelegationResolver() {
        this.capabilityRegistry = null;
    }

    /**
     * Returns a different authorized role for the tool, if one is known.
     * Garuda and skill-only host execution are intentionally not redirected
     * by this resolver.
     */
    public Optional<String> resolveOwner(String currentRole, String toolName) {
        if (toolName == null
                || toolName.isBlank()
                || ToolCapability.DELEGATE_TO_AGENT.toolName().equals(toolName)
                || ToolCapability.HOST_EXEC.toolName().equals(toolName)) {
            return Optional.empty();
        }

        String current = canonicalRole(currentRole);
        if (!AgentRoleEnum.isKnownRole(current)
                && !AgentRoleEnum.isSkillRole(current)
                && (capabilityRegistry == null
                        || capabilityRegistry.getSupplementalTools(current).isEmpty())) {
            return Optional.empty();
        }
        if (isAuthorized(current, toolName)) {
            return Optional.empty();
        }

        String explicitOwner = EXPLICIT_OWNERS.get(toolName);
        if (explicitOwner != null && isAuthorized(explicitOwner, toolName) && !explicitOwner.equals(current)) {
            return Optional.of(explicitOwner);
        }

        if (capabilityRegistry == null) {
            return Optional.empty();
        }
        return capabilityRegistry.rolesWithCapability(toolName).stream()
                .map(this::canonicalRole)
                .filter(role -> !role.equals(current))
                .filter(role -> !role.isBlank())
                .filter(role -> !AgentRoleEnum.isSkillRole(role) || capabilityRegistry.hasCapability(role, toolName))
                .sorted(Comparator.comparingInt(ToolDelegationResolver::fallbackPriority)
                        .thenComparing(Comparator.naturalOrder()))
                .findFirst();
    }

    private static int fallbackPriority(String role) {
        int priority = FALLBACK_OWNER_PRIORITY.indexOf(role);
        return priority >= 0 ? priority : Integer.MAX_VALUE;
    }

    /** Returns whether the resolver has a static ownership rule for a tool. */
    public boolean hasExplicitOwner(String toolName) {
        return EXPLICIT_OWNERS.containsKey(toolName);
    }

    private boolean isAuthorized(String role, String toolName) {
        if (capabilityRegistry != null) {
            return capabilityRegistry.hasCapability(role, toolName);
        }
        Set<String> explicitGrants = EXPLICIT_GRANTS.get(toolName);
        if (explicitGrants != null && explicitGrants.contains(role)) {
            return true;
        }
        return false;
    }

    private String canonicalRole(String role) {
        if (role == null || role.isBlank()) {
            return "";
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        try {
            return AgentRoleEnum.fromString(normalized).toScopedName();
        } catch (IllegalArgumentException e) {
            return normalized;
        }
    }

    /** Visible for all-persona delegation coverage tests. */
    static Set<String> builtInRoles() {
        return ALL_BUILT_IN_ROLES;
    }
}
