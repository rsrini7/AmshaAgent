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
    public static final String OPTIMIZER_ID   = "optimizer";
    public static final String SENTINEL_ID    = "sentinel";
    public static final String HEALER_ID      = "healer";
    public static final String PROTECTOR_ID   = "protector";
    public static final String PURIFIER_ID    = "purifier";
    public static final String NURTURER_ID    = "nurturer";

    public static final AgentRoleEnum ARCHITECT   = new AgentRoleEnum(ARCHITECT_ID);
    public static final AgentRoleEnum COORDINATOR = new AgentRoleEnum(COORDINATOR_ID);
    public static final AgentRoleEnum RESEARCHER  = new AgentRoleEnum(RESEARCHER_ID);
    public static final AgentRoleEnum CODER       = new AgentRoleEnum(CODER_ID);
    public static final AgentRoleEnum DEBUGGER    = new AgentRoleEnum(DEBUGGER_ID);
    public static final AgentRoleEnum SYNTHESIZER = new AgentRoleEnum(SYNTHESIZER_ID);
    public static final AgentRoleEnum OPTIMIZER   = new AgentRoleEnum(OPTIMIZER_ID);
    public static final AgentRoleEnum SENTINEL    = new AgentRoleEnum(SENTINEL_ID);
    public static final AgentRoleEnum HEALER      = new AgentRoleEnum(HEALER_ID);
    public static final AgentRoleEnum PROTECTOR   = new AgentRoleEnum(PROTECTOR_ID);
    public static final AgentRoleEnum PURIFIER    = new AgentRoleEnum(PURIFIER_ID);
    public static final AgentRoleEnum NURTURER    = new AgentRoleEnum(NURTURER_ID);

    private static final List<AgentRoleEnum> BUILT_IN = List.of(
        CODER, DEBUGGER, RESEARCHER, COORDINATOR, ARCHITECT, SYNTHESIZER,
        OPTIMIZER, SENTINEL, HEALER, PROTECTOR, PURIFIER, NURTURER);

    public static final String SKILL_PREFIX = "skill:";

    private static final Map<String, RoleDefinition> DEFINITIONS = Map.ofEntries(
        Map.entry(AgentPersona.BRIHASPATI.roleId(), new RoleDefinition(
            AgentPersona.BRIHASPATI.roleId(),
            AgentPersona.BRIHASPATI.displayName(),
            "Designs system architecture, evaluates technical trade-offs, and authors ADRs.",
            7, true, false, Set.of(),
            AgentPersona.BRIHASPATI.aliases()
        )),
        Map.entry(AgentPersona.VISHNU.roleId(), new RoleDefinition(
            AgentPersona.VISHNU.roleId(),
            AgentPersona.VISHNU.displayName(),
            "Orchestrates sub-agents, manages session state, and schedules background tasks.",
            7, true, false, Set.of(),
            AgentPersona.VISHNU.aliases()
        )),
        Map.entry(AgentPersona.SARASWATI.roleId(), new RoleDefinition(
            AgentPersona.SARASWATI.roleId(),
            AgentPersona.SARASWATI.displayName(),
            "Perform information retrieval, web searches, and documentation lookups.",
            10, true, false, Set.of(),
            AgentPersona.SARASWATI.aliases()
        )),
        Map.entry(AgentPersona.BRAHMA.roleId(), new RoleDefinition(
            AgentPersona.BRAHMA.roleId(),
            AgentPersona.BRAHMA.displayName(),
            "Writes new architectural code, features from scratch, and logic implementations.",
            15, false, true, Set.of(),
            AgentPersona.BRAHMA.aliases()
        )),
        Map.entry(AgentPersona.SHIVA.roleId(), new RoleDefinition(
            AgentPersona.SHIVA.roleId(),
            AgentPersona.SHIVA.displayName(),
            "Analyzes stack traces, finds bugs, and fixes broken code.",
            15, false, true, Set.of(),
            AgentPersona.SHIVA.aliases()
        )),
        Map.entry(AgentPersona.DATTATREYA.roleId(), new RoleDefinition(
            AgentPersona.DATTATREYA.roleId(),
            AgentPersona.DATTATREYA.displayName(),
            "Cross-domain synthesis tasks requiring code, debug, research, and architecture " +
            "simultaneously. Activated when a single task spans multiple specialist domains " +
            "and their outputs need integration or conflict resolution.",
            10, true, false, Set.of(),
            AgentPersona.DATTATREYA.aliases()
        )),
        Map.entry(AgentPersona.GANESHA.roleId(), new RoleDefinition(
            AgentPersona.GANESHA.roleId(),
            AgentPersona.GANESHA.displayName(),
            "Complex logic analysis, obstacle removal, and recursive problem-solving. " +
            "Sub-agent of Shiva for multi-step debugging and logical deduction.",
            10, false, true, Set.of(),
            AgentPersona.GANESHA.aliases()
        )),
        Map.entry(AgentPersona.KARTIKEYA.roleId(), new RoleDefinition(
            AgentPersona.KARTIKEYA.roleId(),
            AgentPersona.KARTIKEYA.displayName(),
            "Security scanning, input validation, and defense against malicious patterns. " +
            "Sub-agent of Shiva for security analysis and guardrail enforcement.",
            10, false, true, Set.of(),
            AgentPersona.KARTIKEYA.aliases()
        )),
        Map.entry(AgentPersona.LAKSHMI.roleId(), new RoleDefinition(
            AgentPersona.LAKSHMI.roleId(),
            AgentPersona.LAKSHMI.displayName(),
            "Error recovery, self-healing, and token budget optimization. " +
            "Manages system health and resource efficiency.",
            10, true, false, Set.of(),
            AgentPersona.LAKSHMI.aliases()
        )),
        Map.entry(AgentPersona.DURGA.roleId(), new RoleDefinition(
            AgentPersona.DURGA.roleId(),
            AgentPersona.DURGA.displayName(),
            "Policy enforcement, guardrails, and autonomy boundary protection. " +
            "Ensures system operates within defined constraints.",
            10, true, false, Set.of(),
            AgentPersona.DURGA.aliases()
        )),
        Map.entry(AgentPersona.KALI.roleId(), new RoleDefinition(
            AgentPersona.KALI.roleId(),
            AgentPersona.KALI.displayName(),
            "Purification, cleanup, and resource lifecycle management. " +
            "Handles session cleanup, data expiration, and graceful shutdown.",
            10, true, false, Set.of(),
            AgentPersona.KALI.aliases()
        )),
        Map.entry(AgentPersona.PARVATI.roleId(), new RoleDefinition(
            AgentPersona.PARVATI.roleId(),
            AgentPersona.PARVATI.displayName(),
            "Empathy, tone analysis, and user sentiment management. " +
            "Ensures responses are appropriately voiced and emotionally intelligent.",
            10, true, false, Set.of(),
            AgentPersona.PARVATI.aliases()
        ))
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
