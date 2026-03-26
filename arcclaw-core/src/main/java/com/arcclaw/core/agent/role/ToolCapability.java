package com.arcclaw.core.agent.role;

import io.quarkus.qute.TemplateData;

/**
 * Defines the canonical tools available to agents within the ArcClaw ecosystem.
 * <p>
 * These capabilities map directly to the {@code AgentTool.name()} identifier
 * and are used within {@link RoleDefinition} to enforce strict role boundaries.
 */
@TemplateData   // makes all fields/getters visible to Qute in native
public enum ToolCapability {

    /**
     * Read-only integration with GitHub (repositories, issues, PRs, gists).
     */
    GITHUB("github", "Read-only access to GitHub repositories and resources"),

    /**
     * Ability to schedule a future or recurring execution of an agent goal.
     */
    SCHEDULE_TASK("schedule_task", "Schedule future or continuous background tasks"),

    /**
     * Ability to list or delete previously scheduled tasks.
     */
    MANAGE_SCHEDULE("manage_schedule", "Manage (list/delete) existing scheduled tasks"),

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
    SANDBOX_EXEC("sandbox_exec",
        "Execute code in an isolated sandbox. " +
        "REQUIRED: 'code' (string), 'language' (one of: python, python3, bash, node, java, go). " +
        "OPTIONAL: 'args' (list), 'secrets' (map). " +
        "Interpreted (python/node/bash): runs inline. " +
        "Compiled (java): needs a complete class with main(). Filename = class name. " +
        "Go: needs complete program with 'package main' and 'func main()'. " +
        "ALWAYS pass flat JSON. Examples: " +
        "{\"code\":\"System.out.println(\\\"Hello\\\");\",\"language\":\"java\"} — WRONG (no class). " +
        "{\"code\":\"public class Main{public static void main(String[] a){System.out.println(\\\"Hello\\\");}}\",\"language\":\"java\"} — CORRECT."),

    /**
     * Ability to ingest a document into the RAG knowledge base.
     */
    INGEST_DOCUMENT("ingest_document", "Ingest a document into the RAG knowledge base");

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
