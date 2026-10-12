package com.amsha.tool.api;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.quarkus.qute.TemplateData;

@TemplateData
public enum ToolCapability {
    GITHUB(ToolNames.GITHUB, ToolCategory.INTERNAL, "Read-only access to GitHub repositories and resources"),

    SCHEDULE_TASK(ToolNames.SCHEDULE_TASK, ToolCategory.INTERNAL, "Schedule future or continuous background tasks"),

    MANAGE_SCHEDULE(ToolNames.MANAGE_SCHEDULE, ToolCategory.INTERNAL, "Manage (list/delete) existing scheduled tasks"),

    RECALL_MEMORY_SEARCH(
            ToolNames.RECALL_MEMORY_SEARCH,
            ToolCategory.DATABASE,
            "Exact search across preserved conversation history (raw messages, code, errors)"),

    ARCHIVAL_MEMORY_SEARCH(
            ToolNames.ARCHIVAL_MEMORY_SEARCH,
            ToolCategory.DATABASE,
            "Semantic search across long-term archival knowledge (typed chunks, patterns, facts, preferences)"),

    CORE_MEMORY_READ(
            ToolNames.CORE_MEMORY_READ,
            ToolCategory.DATABASE,
            "Read core memory blocks (user profile, active task, constraints, project context, preferences)"),

    CORE_MEMORY_APPEND(
            ToolNames.CORE_MEMORY_APPEND,
            ToolCategory.DATABASE,
            "Append or create a core memory block with CAS versioning (5 writes/minute)"),

    CORE_MEMORY_REPLACE(
            ToolNames.CORE_MEMORY_REPLACE,
            ToolCategory.DATABASE,
            "Replace a core memory block with CAS versioning (5 writes/minute)"),

    ARCHIVAL_MEMORY_INSERT(
            ToolNames.ARCHIVAL_MEMORY_INSERT,
            ToolCategory.DATABASE,
            "Insert a knowledge chunk into long-term archival memory (approval-gated, 10 inserts/hour)"),

    BROWSER_NAVIGATE(ToolNames.BROWSER_NAVIGATE, ToolCategory.NETWORK, "Headless browser navigation (Playwright)"),

    SANDBOX_EXEC(
            ToolNames.SANDBOX_EXEC,
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

    HOST_EXEC(
            ToolNames.HOST_EXEC,
            ToolCategory.COMPUTE,
            "Execute a command directly on the host (bypassing the Podman sandbox). "
                    + "REQUIRED: 'code' (string), 'language' (one of: bash, java). "
                    + "OPTIONAL: 'args' (list). "
                    + "Bash: writes code to <workspace>/script.sh and runs it. "
                    + "Java: writes a .java file, compiles via javac, then runs the resulting class. "
                    + "SAFETY: this tool always requires HITL approval; it is NOT granted to any default role. "
                    + "The runtime capability grant is configured via amsha.tools.host-exec.skills. "
                    + "Example: {\"code\":\"echo hello\",\"language\":\"bash\"}. "
                    + "Use host_exec for direct host debugging and diagnostics. "
                    + "For investigating host JVM processes or port listeners, run host_exec with full command-line output (e.g. `ps -axww` or `ps -eo pid,comm,args`) "
                    + "and validate listeners with `lsof -iTCP:<port> -sTCP:LISTEN`, `ss -ltnp | grep <port>`, or `jcmd <pid> VM.system_properties`. "
                    + "Do not rely on truncated short ps output for host process inspection."),

    DELEGATE_TO_AGENT(
            ToolNames.DELEGATE_TO_AGENT,
            ToolCategory.INTERNAL,
            "Garuda - The divine vahana (vehicle) of Vishnu. Use Garuda to dispatch tasks to domain expert sub-agents. "
                    + "REQUIRED: 'agent' (one of: shiva, brahma, saraswati, brihaspati, dattatreya, ganesha, kartikeya, lakshmi, durga, kali, parvati, coordinator), 'goal' (string task description)."),

    INGEST_DOCUMENT(ToolNames.INGEST_DOCUMENT, ToolCategory.DATABASE, "Ingest a document into the RAG knowledge base"),

    MANAGE_CHECKLIST(
            ToolNames.MANAGE_CHECKLIST,
            ToolCategory.DATABASE,
            "Manage a project checklist to track multi-step execution progress."),

    REGISTER_MCP_SERVER(
            ToolNames.REGISTER_MCP_SERVER,
            ToolCategory.INTERNAL,
            "Dynamically register a new MCP server (HTTP or STDIO) into the agent's toolbelt."),

    WEB_SEARCH_BRAVE(
            ToolNames.WEB_SEARCH_BRAVE,
            ToolCategory.NETWORK,
            "Search the web for real-time information using the Brave API."),

    WEB_SEARCH_SERPER(
            ToolNames.WEB_SEARCH_SERPER,
            ToolCategory.NETWORK,
            "Search the web for real-time Google results using the Serper API. "
                    + "Prefer this when AMSHA_TOOLS_SERPER_API_KEY is set (2500 free calls)."),

    AGENT_TASK(
            ToolNames.AGENT_TASK,
            ToolCategory.DATABASE,
            "Create, list, update, and transition markdown-based agent task files."),

    WEB_FETCH(
            ToolNames.WEB_FETCH,
            ToolCategory.NETWORK,
            "Fetch a URL and return its content as plain text (lightweight HTTP GET)."),

    SELF_EVOLVE_REFLECT(
            ToolNames.SELF_EVOLVE_REFLECT,
            ToolCategory.INTERNAL,
            "Trigger self-evolution directive synthesis. Analyzes extracted memory patterns and synthesizes "
                    + "behavioral directives to improve future agent behavior. "
                    + "New directives start in SHADOW mode for safe evaluation. "
                    + "Use when explicitly asked to reflect, learn, or improve behavior.");

    private final String toolName;
    private final ToolCategory category;
    private final String description;

    private static final Map<String, ToolCapability> BY_TOOL_NAME =
            Map.copyOf(Arrays.stream(values()).collect(Collectors.toMap(c -> c.toolName, c -> c)));

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

    /** Pinned delegatable-role vocabulary; drift-checked against core personas by CoreRoleVocabularyDriftTest. */
    public static final String DELEGATABLE_ROLE_NAMES =
            "architect, brahaspati, brahma, brihaspati, coder, coordinator, datta, dattatreya, debugger, "
                    + "durga, ganesh, ganesha, healer, kali, kartikeya, lakshmi, murugan, nurturer, optimizer, "
                    + "parvati, protector, purifier, researcher, saraswati, sentinel, shiva, skanda, synthesizer, "
                    + "uma, vishnu";

    public String description() {
        if (this == DELEGATE_TO_AGENT) {
            String delegatableRoles = DELEGATABLE_ROLE_NAMES;

            return "Delegate a task to a domain expert sub-agent. " + "REQUIRED: 'agent' (string name: "
                    + delegatableRoles + "), 'goal' (string self-contained task description). "
                    + "Example: {\"agent\":\"shiva\",\"goal\":\"Analyze the stack trace in app.log and fix the NPE\"}";
        }
        return description;
    }
}
