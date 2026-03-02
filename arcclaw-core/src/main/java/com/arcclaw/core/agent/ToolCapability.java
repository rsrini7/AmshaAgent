package com.arcclaw.core.agent;

/**
 * Defines the canonical tools available to agents within the ArcClaw ecosystem.
 * <p>
 * These capabilities map directly to the {@code AgentTool.name()} identifier
 * and are used within {@link RoleDefinition} to enforce strict role boundaries.
 */
public enum ToolCapability {

    /**
     * Read-only integration with GitHub (repositories, issues, PRs, gists).
     */
    GITHUB("github", "Read-only access to GitHub repositories and resources"),

    /**
     * Ability to schedule a future or recurring execution of an agent goal.
     */
    SCHEDULE_TASK("scheduletask", "Schedule future or continuous background tasks"),

    /**
     * Ability to list or delete previously scheduled tasks.
     */
    MANAGE_SCHEDULE("manageschedule", "Manage (list/delete) existing scheduled tasks"),

    /**
     * Read-only vector search against the agent's long-term memory store.
     */
    MEMORY_SEARCH("memory_search", "Semantic search across historical session memories"),

    /**
     * Ability to execute untrusted code in an ephemeral Podman container.
     * <p>
     * <b>Note:</b> Deferred component; presence in schema does not imply
     * execution support until Phase 48 container integration is complete.
     */
    SANDBOX_EXEC("sandboxexec", "Execute code within an isolated Podman sandbox");

    private final String toolName;
    private final String description;

    ToolCapability(String toolName, String description) {
        this.toolName = toolName;
        this.description = description;
    }

    /**
     * Returns the canonical string identifier of the tool.
     * This string matches the value returned by {@code AgentTool.name()}.
     *
     * @return the unique tool identifier.
     */
    public String toolName() {
        return toolName;
    }

    /**
     * Returns a human-readable description of the tool's capability.
     *
     * @return the description.
     */
    public String description() {
        return description;
    }
}
