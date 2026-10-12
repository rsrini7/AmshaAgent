package com.amsha.tool.api;

/**
 * Canonical tool-name constants, shared by {@link ToolCapability} and the
 * {@code @ToolMetadata(capabilityId = ...)} declaration on each tool class.
 * <p>
 * The name is intentionally declared in two places today — the capability registry
 * and the tool's own annotation — so a rename in one without the other would leave
 * a tool registered under a name the registry no longer knows. Annotation attributes
 * must be compile-time constants, so {@link ToolCapability#toolName()} cannot be
 * used there; reference these constants instead. At runtime prefer
 * {@link ToolCapability#toolName()} (AGENTS.md).
 * <p>
 * Note: {@code "github"} is also an OAuth identity-provider id
 * ({@code ExternalIdentityProvider}); that namespace is separate and must not use
 * these constants.
 */
public final class ToolNames {

    public static final String GITHUB = "github";
    public static final String SCHEDULE_TASK = "schedule_task";
    public static final String MANAGE_SCHEDULE = "manage_schedule";
    public static final String RECALL_MEMORY_SEARCH = "recall_memory_search";
    public static final String ARCHIVAL_MEMORY_SEARCH = "archival_memory_search";
    public static final String CORE_MEMORY_READ = "core_memory_read";
    public static final String CORE_MEMORY_APPEND = "core_memory_append";
    public static final String CORE_MEMORY_REPLACE = "core_memory_replace";
    public static final String ARCHIVAL_MEMORY_INSERT = "archival_memory_insert";
    public static final String BROWSER_NAVIGATE = "browser_navigate";
    public static final String SANDBOX_EXEC = "sandbox_exec";
    public static final String HOST_EXEC = "host_exec";
    public static final String DELEGATE_TO_AGENT = "garuda";
    public static final String INGEST_DOCUMENT = "ingest_document";
    public static final String MANAGE_CHECKLIST = "manage_checklist";
    public static final String REGISTER_MCP_SERVER = "register_mcp_server";
    public static final String WEB_SEARCH_BRAVE = "brave_web_search";
    public static final String WEB_SEARCH_SERPER = "serper_web_search";
    public static final String AGENT_TASK = "agent_task";
    public static final String WEB_FETCH = "web_fetch";
    public static final String SELF_EVOLVE_REFLECT = "self_evolve_reflect";

    private ToolNames() {
        // Prevent instantiation
    }
}
