package com.arcclaw.core.agent.role;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
     * Ability to navigate the web with a fully-featured headless browser.
     */
    BROWSER_NAVIGATE("browser_navigate", "Headless browser navigation (Playwright)"),

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
        "CRITICAL: The 'code' parameter MUST be a plain string of source code. NEVER wrap the source code in an additional JSON object inside the string. " +
        "Examples: " +
        "{\"code\":\"System.out.println(\\\"Hello\\\");\",\"language\":\"java\"} - WRONG (no class). " +
        "{\"code\":\"public class Main{public static void main(String[] a){System.out.println(\\\"Hello\\\");}}\",\"language\":\"java\"} - CORRECT. " +
        "Keep your JSON payload under 2000 characters to prevent max_tokens truncation. For short snippets, use a compact single-line form without newlines."),

    /**
     * Ability to delegate tasks to a domain expert sub-agent.
     * <p>
     * REQUIRED: 'agent' (string name of the role/deity), 'goal' (string task description).
     * The goal must be fully self-contained. Do not say "fix my code", say
     * "fix the NullPointerException in UserAuth.java on line 42".
     * Example: {"agent":"shiva","goal":"Analyze stack trace in app.log and fix the NPE"}
     */
    DELEGATE_TO_AGENT("delegate_to_agent",
        "Delegate a task to a domain expert sub-agent. " +
        "REQUIRED: 'agent' (one of: shiva, brahma, saraswati, brihaspati, dattatreya), 'goal' (string task description)."),

    /**
     * Ability to ingest a document into the RAG knowledge base.
     */
    INGEST_DOCUMENT("ingest_document", "Ingest a document into the RAG knowledge base"),

    /**
     * Ability to manage a multi-step checklist for progress tracking.
     */
    MANAGE_CHECKLIST("check_list", "Manage a project checklist to track multi-step execution progress."),

    /**
     * Ability to dynamically register a new MCP server.
     */
    REGISTER_MCP_SERVER("add_mcp_server", "Dynamically register a new MCP server (HTTP or STDIO) into the agent's toolbelt."),

    /**
     * Ability to search the web using Brave Search API.
     */
    WEB_SEARCH_BRAVE("brave_web_search", "Search the web for real-time information using the Brave API."),
    
    /**
     * Ability to search the web using Serper (Google Search API).
     */
    WEB_SEARCH_SERPER("serper_web_search",
        "Search the web for real-time Google results using the Serper API. " +
        "Prefer this when ARCCLAW_TOOLS_SERPER_API_KEY is set (2500 free calls)."),

    /**
     * Task management — create, list, update, and transition agent task files.
     */
    AGENT_TASK("agent_task", "Create, list, update, and transition markdown-based agent task files."),

    /**
     * Lightweight web fetch — HTTP GET + HTML-to-text. No browser, no LLM tokens.
     * Use for reading articles, documentation, and static pages.
     */
    WEB_FETCH("web_fetch", "Fetch a URL and return its content as plain text (lightweight HTTP GET).");

    private final String toolName;
    private final String description;

    private static final Map<String, ToolCapability> BY_TOOL_NAME =
            Arrays.stream(values()).collect(Collectors.toMap(c -> c.toolName, c -> c));

    ToolCapability(String toolName, String description) {
        this.toolName = toolName;
        this.description = description;
    }

    /**
     * Returns true if this capability requires a headless browser (Playwright).
     */
    public boolean isBrowserBacked() {
        return this == BROWSER_NAVIGATE || this == WEB_SEARCH_SERPER;
    }

    /**
     * Resolves a tool identifier to its corresponding capability.
     * 
     * @param toolName the tool identifier string.
     * @return an Optional containing the ToolCapability if found.
     */
    public static Optional<ToolCapability> fromToolName(String toolName) {
        if (toolName == null) return Optional.empty();
        return Optional.ofNullable(BY_TOOL_NAME.get(toolName));
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
        if (this == DELEGATE_TO_AGENT) {
            String delegatableRoles = AgentRoleEnum.values().stream()
                .filter(r -> !r.equals(AgentRoleEnum.COORDINATOR))
                .flatMap(r -> r.definition().aliases().stream())
                .sorted()
                .collect(Collectors.joining(", "));
            
            return "Delegate a task to a domain expert sub-agent. " +
                   "REQUIRED: 'agent' (string name: " + delegatableRoles + "), 'goal' (string self-contained task description). " +
                   "Example: {\"agent\":\"shiva\",\"goal\":\"Analyze the stack trace in app.log and fix the NPE\"}";
        }
        return description;
    }
}
