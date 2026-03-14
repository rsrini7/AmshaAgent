package com.arcclaw.core.agent.role;

import java.util.Set;

import io.quarkus.qute.TemplateData;

/**
 * Type-safe registry of all permitted agent roles in the ArcClaw system.
 * Prevents magic-string routing errors and typo-driven persona switching.
 */
@TemplateData
public enum AgentRoleEnum {
    ARCHITECT("architect", new RoleDefinition(
            "architect",
            "Architect (System Designer)",
            "Designs system architecture, evaluates technical trade-offs, and authors ADRs.",
            7,
            true,
            Set.of(ToolCapability.GITHUB, ToolCapability.MEMORY_SEARCH)
    )),

    COORDINATOR("coordinator", new RoleDefinition(
            "coordinator",
            "Vishnu (Coordinator)",
            "Orchestrates sub-agents, manages session state, and schedules background tasks.",
            7,
            true,
            Set.of(ToolCapability.GITHUB, ToolCapability.SCHEDULE_TASK, ToolCapability.MANAGE_SCHEDULE, ToolCapability.MEMORY_SEARCH)
    )),

    RESEARCHER("researcher", new RoleDefinition(
            "researcher",
            "Saraswati (Researcher)",
            "Perform information retrieval, web searches, and documentation lookups.",
            10,
            true,
            Set.of(ToolCapability.GITHUB, ToolCapability.MEMORY_SEARCH)
    )),

    CODER("coder", new RoleDefinition(
            "coder",
            "Brahma (Creator)",
            "Writes new architectural code, features from scratch, and logic implementations.",
            3,
            false,
            Set.of() // SANDBOX_EXEC deferred to Phase 48
    )),

    DEBUGGER("debugger", new RoleDefinition(
            "debugger",
            "Shiva (Destroyer/Debugger)",
            "Analyzes stack traces, finds bugs, and fixes broken code.",
            3,
            false,
            Set.of(ToolCapability.GITHUB)
    )),

    DATTATREYA("synthesizer", new RoleDefinition(
            "synthesizer",
            "Dattatreya (Synthesizer)",
            "Cross-domain synthesis tasks requiring code, debug, research, and architecture " +
            "simultaneously. Activated when a single task spans multiple specialist domains " +
            "and their outputs need integration or conflict resolution.",
            10,
            true,
            Set.of(ToolCapability.GITHUB, ToolCapability.MEMORY_SEARCH,
                             ToolCapability.SCHEDULE_TASK, ToolCapability.MANAGE_SCHEDULE)
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
     * <p>
     * The {@code skill:} prefix (produced by {@code SkillAwareIntentClassifier}) is
     * handled here to prevent {@link IllegalArgumentException} and the silent
     * COORDINATOR fallback in {@code AgentLoop}. Skills use RESEARCHER as their base
     * role because it provides GITHUB + MEMORY_SEARCH — the capabilities required by
     * all current skill definitions. The actual LLM persona and system prompt are
     * overridden by {@code SystemPromptBuilder} using the skill's own definition;
     * this enum value only governs the tool-binding layer.
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

        // Skill roles produced by SkillAwareIntentClassifier (e.g. "skill:code-reviewer").
        // Use RESEARCHER as the base role — provides GITHUB + MEMORY_SEARCH which matches
        // current skill tool-capability declarations. SystemPromptBuilder replaces the
        // persona prompt with the skill's own system prompt before the LLM call.
        if (normalized.startsWith("skill:")) {
            return RESEARCHER;
        }

        return switch (normalized) {
            case "architect", "brihaspati", "brahaspati" -> ARCHITECT;
            case "coordinator", "vishnu" -> COORDINATOR;
            case "researcher", "saraswati" -> RESEARCHER;
            case "coder", "brahma" -> CODER;
            case "debugger", "shiva" -> DEBUGGER;
            case "synthesizer", "dattatreya", "datta" -> DATTATREYA;
            default -> throw new IllegalArgumentException("Unknown AgentRole: " + roleStr);
        };
    }
}
