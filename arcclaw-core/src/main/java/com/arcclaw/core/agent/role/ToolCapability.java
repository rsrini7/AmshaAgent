package com.arcclaw.core.agent.role;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.arcclaw.core.tools.ToolCategory;

import io.quarkus.qute.TemplateData;

@TemplateData
public enum ToolCapability {
    GITHUB("github", ToolCategory.INTERNAL, "Read-only access to GitHub repositories and resources"),

    SCHEDULE_TASK("schedule_task", ToolCategory.INTERNAL, "Schedule future or continuous background tasks"),

    MANAGE_SCHEDULE("manage_schedule", ToolCategory.INTERNAL, "Manage (list/delete) existing scheduled tasks"),

    RECALL_MEMORY_SEARCH(
            "recall_memory_search",
            ToolCategory.DATABASE,
            "Exact search across preserved conversation history (raw messages, code, errors)"),

    ARCHIVAL_MEMORY_SEARCH(
            "archival_memory_search",
            ToolCategory.DATABASE,
            "Semantic search across long-term archival knowledge (typed chunks, patterns, facts, preferences)"),

    CORE_MEMORY_READ(
            "core_memory_read",
            ToolCategory.DATABASE,
            "Read core memory blocks (user profile, active task, constraints, project context, preferences)"),

    CORE_MEMORY_APPEND(
            "core_memory_append",
            ToolCategory.DATABASE,
            "Append or create a core memory block with CAS versioning (5 writes/minute)"),

    CORE_MEMORY_REPLACE(
            "core_memory_replace",
            ToolCategory.DATABASE,
            "Replace a core memory block with CAS versioning (5 writes/minute)"),

    ARCHIVAL_MEMORY_INSERT(
            "archival_memory_insert",
            ToolCategory.DATABASE,
            "Insert a knowledge chunk into long-term archival memory (approval-gated, 10 inserts/hour)"),

    BROWSER_NAVIGATE("browser_navigate", ToolCategory.NETWORK, "Headless browser navigation (Playwright)"),

    SANDBOX_EXEC(
            "sandbox_exec",
            ToolCategory.COMPUTE,
            "Execute code in an isolated sandbox. "
                    + "REQUIRED: 'code' (string), 'language' (one of: python, python3, bash, node, java, go). "
                    + "OPTIONAL: 'args' (list), 'secrets' (map). "
                    + "Interpreted (python/node/bash): runs inline. "
                    + "Compiled (java): needs a complete class with main(). Filename = class name. "
                    + "Go: needs complete program with 'package main' and 'func main()'. "
                    + "CRITICAL: The 'code' parameter MUST be a plain string of source code. NEVER wrap the source code in an additional JSON object inside the string. "
                    + "Examples: "
                    + "{\"code\":\"System.out.println(\\\"Hello\\\");\",\"language\":\"java\"} - WRONG (no class). "
                    + "{\"code\":\"public class Main{public static void main(String[] a){System.out.println(\\\"Hello\\\");}}\",\"language\":\"java\"} - CORRECT. "
                    + "Keep your JSON payload under 2000 characters to prevent max_tokens truncation. For short snippets, use a compact single-line form without newlines."),

    DELEGATE_TO_AGENT(
            "garuda",
            ToolCategory.INTERNAL,
            "Garuda - The divine vahana (vehicle) of Vishnu. Use Garuda to dispatch tasks to domain expert sub-agents. "
                    + "REQUIRED: 'agent' (one of: shiva, brahma, saraswati, brihaspati, dattatreya, ganesha, kartikeya, lakshmi, durga, kali, parvati, coordinator), 'goal' (string task description)."),

    INGEST_DOCUMENT("ingest_document", ToolCategory.DATABASE, "Ingest a document into the RAG knowledge base"),

    MANAGE_CHECKLIST(
            "check_list", ToolCategory.DATABASE, "Manage a project checklist to track multi-step execution progress."),

    REGISTER_MCP_SERVER(
            "register_mcp_server",
            ToolCategory.INTERNAL,
            "Dynamically register a new MCP server (HTTP or STDIO) into the agent's toolbelt."),

    WEB_SEARCH_BRAVE(
            "brave_web_search", ToolCategory.NETWORK, "Search the web for real-time information using the Brave API."),

    WEB_SEARCH_SERPER(
            "serper_web_search",
            ToolCategory.NETWORK,
            "Search the web for real-time Google results using the Serper API. "
                    + "Prefer this when ARCCLAW_TOOLS_SERPER_API_KEY is set (2500 free calls)."),

    AGENT_TASK(
            "agent_task",
            ToolCategory.DATABASE,
            "Create, list, update, and transition markdown-based agent task files."),

    WEB_FETCH(
            "web_fetch",
            ToolCategory.NETWORK,
            "Fetch a URL and return its content as plain text (lightweight HTTP GET).");

    private final String toolName;
    private final ToolCategory category;
    private final String description;

    private static final Map<String, ToolCapability> BY_TOOL_NAME =
            Arrays.stream(values()).collect(Collectors.toMap(c -> c.toolName, c -> c));

    ToolCapability(String toolName, ToolCategory category, String description) {
        this.toolName = toolName;
        this.category = category;
        this.description = description;
    }

    public boolean isBrowserBacked() {
        return this == BROWSER_NAVIGATE || this == WEB_SEARCH_SERPER;
    }

    public ToolCategory category() {
        return category;
    }

    public static Optional<ToolCapability> fromToolName(String toolName) {
        if (toolName == null) return Optional.empty();
        return Optional.ofNullable(BY_TOOL_NAME.get(toolName));
    }

    public String toolName() {
        return toolName;
    }

    public String description() {
        if (this == DELEGATE_TO_AGENT) {
            String delegatableRoles = AgentRoleEnum.values().stream()
                    .filter(r -> !r.equals(AgentRoleEnum.COORDINATOR))
                    .flatMap(r -> r.definition().aliases().stream())
                    .sorted()
                    .collect(Collectors.joining(", "));

            return "Delegate a task to a domain expert sub-agent. " + "REQUIRED: 'agent' (string name: "
                    + delegatableRoles + "), 'goal' (string self-contained task description). "
                    + "Example: {\"agent\":\"shiva\",\"goal\":\"Analyze the stack trace in app.log and fix the NPE\"}";
        }
        return description;
    }
}
