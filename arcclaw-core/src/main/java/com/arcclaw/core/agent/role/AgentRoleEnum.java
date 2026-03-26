package com.arcclaw.core.agent.role;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.quarkus.qute.TemplateData;

/**
 * Type-safe registry of all permitted agent roles in the ArcClaw system.
 * Prevents magic-string routing errors and typo-driven persona switching.
 */
@TemplateData
public record AgentRoleEnum(String id) {

    public static final AgentRoleEnum ARCHITECT   = new AgentRoleEnum("architect");
    public static final AgentRoleEnum COORDINATOR = new AgentRoleEnum("coordinator");
    public static final AgentRoleEnum RESEARCHER  = new AgentRoleEnum("researcher");
    public static final AgentRoleEnum CODER       = new AgentRoleEnum("coder");
    public static final AgentRoleEnum DEBUGGER    = new AgentRoleEnum("debugger");
    public static final AgentRoleEnum SYNTHESIZER = new AgentRoleEnum("synthesizer");

    private static final List<AgentRoleEnum> BUILT_IN = List.of(
        CODER, DEBUGGER, RESEARCHER, COORDINATOR, ARCHITECT, SYNTHESIZER);

    public static final String SKILL_PREFIX = "skill:";

    private static final Map<String, RoleDefinition> DEFINITIONS = Map.of(
        "architect", new RoleDefinition(
            "architect",
            "Brihaspati (System Designer)",
            "Designs system architecture, evaluates technical trade-offs, and authors ADRs.",
            7, true, Set.of(ToolCapability.GITHUB, ToolCapability.MEMORY_SEARCH), Set.of("brihaspati", "brahaspati")
        ),
        "coordinator", new RoleDefinition(
            "coordinator",
            "Vishnu (Coordinator)",
            "Orchestrates sub-agents, manages session state, and schedules background tasks.",
            7, true, Set.of(ToolCapability.GITHUB, ToolCapability.SCHEDULE_TASK, ToolCapability.MANAGE_SCHEDULE, ToolCapability.MEMORY_SEARCH, ToolCapability.INGEST_DOCUMENT), Set.of("vishnu")
        ),
        "researcher", new RoleDefinition(
            "researcher",
            "Saraswati (Researcher)",
            "Perform information retrieval, web searches, and documentation lookups.",
            10, true, Set.of(ToolCapability.GITHUB, ToolCapability.MEMORY_SEARCH), Set.of("saraswati")
        ),
        "coder", new RoleDefinition(
            "coder",
            "Brahma (Creator)",
            "Writes new architectural code, features from scratch, and logic implementations.",
            3, false, Set.of(ToolCapability.SANDBOX_EXEC), Set.of("brahma")
        ),
        "debugger", new RoleDefinition(
            "debugger",
            "Shiva (Destroyer/Debugger)",
            "Analyzes stack traces, finds bugs, and fixes broken code.",
            3, false, Set.of(ToolCapability.GITHUB, ToolCapability.SANDBOX_EXEC), Set.of("shiva")
        ),
        "synthesizer", new RoleDefinition(
            "synthesizer",
            "Dattatreya (Synthesizer)",
            "Cross-domain synthesis tasks requiring code, debug, research, and architecture " +
            "simultaneously. Activated when a single task spans multiple specialist domains " +
            "and their outputs need integration or conflict resolution.",
            10, true, Set.of(ToolCapability.GITHUB, ToolCapability.MEMORY_SEARCH, ToolCapability.SCHEDULE_TASK, ToolCapability.MANAGE_SCHEDULE, ToolCapability.INGEST_DOCUMENT), Set.of("dattatreya", "datta")
        )
    );

    /** Preserves all existing toScopedName() call sites with no change. */
    public String toScopedName() {
        return id;
    }

    /**
     * @return the definitive capability and operational constraints for this role
     */
    public RoleDefinition definition() {
        RoleDefinition def = DEFINITIONS.get(this.id);
        if (def != null) {
            return def;
        }
        return new RoleDefinition(this.id, "Dynamic Role", "", 5, true, Set.of(), Set.of());
    }

    /** Never throws — unknown names produce a dynamic role. */
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

        return new AgentRoleEnum(normalized);
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
