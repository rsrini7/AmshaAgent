package com.arcclaw.core.agent.role;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.quarkus.qute.TemplateData;

/**
 * Type-safe registry of all permitted agent roles in the ArcClaw system.
 * Prevents magic-string routing errors and typo-driven persona switching.
 */
@TemplateData
public record AgentRoleEnum(String id) {

    public static final String ARCHITECT_ID   = "architect";
    public static final String COORDINATOR_ID = "coordinator";
    public static final String RESEARCHER_ID  = "researcher";
    public static final String CODER_ID       = "coder";
    public static final String DEBUGGER_ID    = "debugger";
    public static final String SYNTHESIZER_ID = "synthesizer";

    public static final AgentRoleEnum ARCHITECT   = new AgentRoleEnum(ARCHITECT_ID);
    public static final AgentRoleEnum COORDINATOR = new AgentRoleEnum(COORDINATOR_ID);
    public static final AgentRoleEnum RESEARCHER  = new AgentRoleEnum(RESEARCHER_ID);
    public static final AgentRoleEnum CODER       = new AgentRoleEnum(CODER_ID);
    public static final AgentRoleEnum DEBUGGER    = new AgentRoleEnum(DEBUGGER_ID);
    public static final AgentRoleEnum SYNTHESIZER = new AgentRoleEnum(SYNTHESIZER_ID);

    private static final List<AgentRoleEnum> BUILT_IN = List.of(
        CODER, DEBUGGER, RESEARCHER, COORDINATOR, ARCHITECT, SYNTHESIZER);

    public static final String SKILL_PREFIX = "skill:";

    private static final Map<String, RoleDefinition> DEFINITIONS = Map.of(
        AgentPersona.BRIHASPATI.roleId(), new RoleDefinition(
            AgentPersona.BRIHASPATI.roleId(),
            AgentPersona.BRIHASPATI.displayName(),
            "Designs system architecture, evaluates technical trade-offs, and authors ADRs.",
            7, true, false, Set.of(), 
            AgentPersona.BRIHASPATI.aliases()
        ),
        AgentPersona.VISHNU.roleId(), new RoleDefinition(
            AgentPersona.VISHNU.roleId(),
            AgentPersona.VISHNU.displayName(),
            "Orchestrates sub-agents, manages session state, and schedules background tasks.",
            7, true, false, Set.of(), 
            AgentPersona.VISHNU.aliases()
        ),
        AgentPersona.SARASWATI.roleId(), new RoleDefinition(
            AgentPersona.SARASWATI.roleId(),
            AgentPersona.SARASWATI.displayName(),
            "Perform information retrieval, web searches, and documentation lookups.",
            10, true, false, Set.of(), 
            AgentPersona.SARASWATI.aliases()
        ),
        AgentPersona.BRAHMA.roleId(), new RoleDefinition(
            AgentPersona.BRAHMA.roleId(),
            AgentPersona.BRAHMA.displayName(),
            "Writes new architectural code, features from scratch, and logic implementations.",
            15, false, true, Set.of(), 
            AgentPersona.BRAHMA.aliases()
        ),
        AgentPersona.SHIVA.roleId(), new RoleDefinition(
            AgentPersona.SHIVA.roleId(),
            AgentPersona.SHIVA.displayName(),
            "Analyzes stack traces, finds bugs, and fixes broken code.",
            15, false, true, Set.of(), 
            AgentPersona.SHIVA.aliases()
        ),
        AgentPersona.DATTATREYA.roleId(), new RoleDefinition(
            AgentPersona.DATTATREYA.roleId(),
            AgentPersona.DATTATREYA.displayName(),
            "Cross-domain synthesis tasks requiring code, debug, research, and architecture " +
            "simultaneously. Activated when a single task spans multiple specialist domains " +
            "and their outputs need integration or conflict resolution.",
            10, true, false, Set.of(), 
            AgentPersona.DATTATREYA.aliases()
        )
    );

    /** Preserves all existing toScopedName() call sites with no change. */
    public String toScopedName() {
        return id;
    }

    /**
     * @return the persona mapped to this role, if it is a built-in role.
     */
    public Optional<AgentPersona> persona() {
        return AgentPersona.fromString(this.id);
    }

    /**
     * @return the definitive capability and operational constraints for this role
     */
    public RoleDefinition definition() {
        RoleDefinition def = DEFINITIONS.get(this.id);
        if (def != null) {
            return def;
        }
        return new RoleDefinition(this.id, "Dynamic Role", "", 5, true, false, Set.of(), Set.of());
    }

    /** Throws IllegalArgumentException for unknown roles, defaulting handled by callers. */
    public static AgentRoleEnum fromString(String roleStr) {
        if (roleStr == null || roleStr.isBlank()) {
            return COORDINATOR;
        }

        String normalized = roleStr.trim().toLowerCase();

        // Skill-based dynamic routing
        if (isSkillRole(normalized)) {
            return RESEARCHER;
        }

        for (AgentRoleEnum builtIn : BUILT_IN) {
            RoleDefinition def = DEFINITIONS.get(builtIn.id());
            if (def.canonicalName().equals(normalized) || def.aliases().contains(normalized)) {
                return builtIn;
            }
        }

        throw new IllegalArgumentException("Unknown agent role: " + roleStr);
    }

    /** Returns true only if the role string maps to a known built-in role. */
    public static boolean isKnownRole(String roleStr) {
        if (roleStr == null || roleStr.isBlank()) return false;
        String normalized = roleStr.trim().toLowerCase();
        if (isSkillRole(normalized)) return true;
        for (AgentRoleEnum builtIn : BUILT_IN) {
            RoleDefinition def = DEFINITIONS.get(builtIn.id());
            if (def.canonicalName().equals(normalized) || def.aliases().contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** Returns true if the role string starts with the skill prefix. */
    public static boolean isSkillRole(String roleStr) {
        return roleStr != null && roleStr.startsWith(SKILL_PREFIX);
    }

    /** Extracts the skill name from a skill role string. */
    public static String skillName(String roleStr) {
        if (!isSkillRole(roleStr)) {
            throw new IllegalArgumentException("Not a skill role: " + roleStr);
        }
        return roleStr.substring(SKILL_PREFIX.length());
    }

    /** Drop-in replacement for the enum-generated values() calls. */
    public static List<AgentRoleEnum> values() {
        return BUILT_IN;
    }
}
