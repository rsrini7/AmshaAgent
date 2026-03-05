package com.arcclaw.core.agent;

/**
 * Type-safe registry of all permitted agent roles in the ArcClaw system.
 * Prevents magic-string routing errors and typo-driven persona switching.
 */
public enum AgentRoleEnum {
    ARCHITECT("architect", new RoleDefinition(
            "architect",
            "Architect (System Designer)",
            "Designs system architecture, evaluates technical trade-offs, and authors ADRs.",
            2,
            true,
            java.util.Set.of(ToolCapability.GITHUB, ToolCapability.MEMORY_SEARCH)
    )),

    COORDINATOR("coordinator", new RoleDefinition(
            "coordinator",
            "Vishnu (Coordinator)",
            "Orchestrates sub-agents, manages session state, and schedules background tasks.",
            5,
            false,
            java.util.Set.of(ToolCapability.GITHUB, ToolCapability.SCHEDULE_TASK, ToolCapability.MANAGE_SCHEDULE)
    )),

    RESEARCHER("researcher", new RoleDefinition(
            "researcher",
            "Saraswati (Researcher)",
            "Perform information retrieval, web searches, and documentation lookups.",
            3,
            false,
            java.util.Set.of(ToolCapability.GITHUB, ToolCapability.MEMORY_SEARCH)
    )),

    CODER("coder", new RoleDefinition(
            "coder",
            "Brahma (Creator)",
            "Writes new architectural code, features from scratch, and logic implementations.",
            3,
            false,
            java.util.Set.of() // SANDBOX_EXEC deferred to Phase 48
    )),

    DEBUGGER("debugger", new RoleDefinition(
            "debugger",
            "Shiva (Destroyer/Debugger)",
            "Analyzes stack traces, finds bugs, and fixes broken code.",
            3,
            false,
            java.util.Set.of(ToolCapability.GITHUB)
    ));

    private final String canonicalName;
    private final RoleDefinition definition;

    AgentRoleEnum(String canonicalName, RoleDefinition definition) {
        this.canonicalName = canonicalName;
        this.definition = definition;
    }

    /**
     * @return lowercase canonical string for LLM prompt injection and JPA storage
     */
    public String toScopedName() {
        return canonicalName;
    }

    /**
     * @return the definitive capability and operational constraints for this role
     */
    public RoleDefinition definition() {
        return definition;
    }

    /**
     * Resolves an AgentRoleEnum from a string, handling both canonical names and
     * the system's Hindu deity aliases.
     *
     * @param roleStr the string identifier from Telegram or existing checkpoints
     * @return the resolved AgentRoleEnum
     * @throws IllegalArgumentException if the role is truly unknown
     */
    public static AgentRoleEnum fromString(String roleStr) {
        if (roleStr == null || roleStr.isBlank()) {
            throw new IllegalArgumentException("AgentRole cannot be null or blank");
        }

        String normalized = roleStr.trim().toLowerCase();

        return switch (normalized) {
            case "architect", "brahaspati" -> ARCHITECT;
            case "coordinator", "vishnu" -> COORDINATOR;
            case "researcher", "saraswati" -> RESEARCHER;
            case "coder", "brahma" -> CODER;
            case "debugger", "shiva" -> DEBUGGER;
            default -> throw new IllegalArgumentException("Unknown AgentRole: " + roleStr);
        };
    }
}
