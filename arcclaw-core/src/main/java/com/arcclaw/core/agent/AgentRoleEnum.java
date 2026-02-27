package com.arcclaw.core.agent;

/**
 * Type-safe registry of all permitted agent roles in the ArcClaw system.
 * Prevents magic-string routing errors and typo-driven persona switching.
 */
public enum AgentRoleEnum {
    ARCHITECT("architect"),
    COORDINATOR("coordinator"),
    RESEARCHER("researcher"),
    CODER("coder"),
    DEBUGGER("debugger");

    private final String canonicalName;

    AgentRoleEnum(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    /**
     * @return lowercase canonical string for LLM prompt injection and JPA storage
     */
    public String toScopedName() {
        return canonicalName;
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
            case "architect" -> ARCHITECT;
            case "coordinator", "vishnu" -> COORDINATOR;
            case "researcher", "saraswati" -> RESEARCHER;
            case "coder", "brahma" -> CODER;
            case "debugger", "shiva" -> DEBUGGER;
            default -> throw new IllegalArgumentException("Unknown AgentRole: " + roleStr);
        };
    }
}
