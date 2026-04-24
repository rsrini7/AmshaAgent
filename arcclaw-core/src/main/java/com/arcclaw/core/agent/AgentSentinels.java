package com.arcclaw.core.agent;

import java.util.regex.Pattern;

/**
 * Centered sentinel strings for specialized agent logic, approval flows,
 * and state-machine transitions.
 */
public final class AgentSentinels {
    private AgentSentinels() {}
    
    /** sentinel for delegated sub-sessions. format: {parent_sid}:delegate:{agentName}:{currentTimeMillis} */
    public static final String DELEGATE_SESSION_SENTINEL = ":delegate:";

    /** User refused the tool execution. matches text in DenyReflex. */
    public static final String DENY_SENTINEL = "Human denied the tool execution.";

    /** Verbatim string currently inlined in ChatMemoryWriter for tool approvals. */
    public static final String GOAL_TOOL_APPROVED =
        "The tool execution has been approved and completed. Read the tool's output. If it is an error, tell me. Otherwise, provide the final answer to my original request.";

    /** Internal goal strings used when resuming a task. */
    public static final String GOAL_RESUME_APPROVED = "Resuming task after approval";
    public static final String GOAL_RESUME_DENIED   = "Resuming task after denial";
    public static final String GOAL_DENY_APPROVED   = "DENY_APPROVED_SENTINEL_V1";
    
    // goal detection
    public static final String CRON_PREFIX = "CRON ";

    // session ID prefix
    public static final String CRON_SESSION_PREFIX = "cron-";

    /** Sentinel used in chat history to identify the distilled context from prior messages. */
    public static final String COMPACTED_SUMMARY_SENTINEL = "[CONTEXT FROM PRIOR SESSION]";

    public static boolean isCronGoal(String goal) {
        return goal != null && goal.toUpperCase().startsWith(CRON_PREFIX);
    }

    public static String stripCronPrefix(String goal) {
        if (!isCronGoal(goal)) return goal;
        return goal.substring(CRON_PREFIX.length()).trim();
    }

    public static final Pattern CONTEXT_RAG_ECHO_PATTERN = Pattern.compile(
         "^<rag_memory>[\\s\\S]*?</rag_memory>\\s*",
        Pattern.CASE_INSENSITIVE
    );

    // Compaction context echo guard 
    // The LLM occasionally pastes the injected "## Retrieved Context" compaction
    // header verbatim into its FINISH answer instead of writing a fresh response.
    // Observed: TELEGRAM-SEND text=[## Retrieved Context\nYou initiated a session...]
    // strip the header (and anything before the first real content line) so
    // the user sees the summary without the raw template heading.
    public static final Pattern CONTEXT_RETRIEVED_ECHO_PATTERN = Pattern.compile(
        "^##\\s*Retrieved Context[^\\n]*\\n+(?:<context>[\\s\\S]*?</context>\\s*)?",
        Pattern.CASE_INSENSITIVE
    );

    // System prompt echo guard
    // The LLM occasionally echoes back its own system prompt (persona, environment
    // metadata, tool list) as part of the FINISH answer. This leaks internal host
    // details (working directory, Java version, Git status) to the end user.
    public static final Pattern SYSTEM_PROMPT_ECHO_PATTERN = Pattern.compile(
        "##\\s*(?:Host Environment Context|Current Context|System Status|Time Status)" +
        "|\\*\\*Working Directory\\*\\*" +
        "|\\*\\*ArcClaw Version\\*\\*" +
        "|\\*\\*Git Repository Detected\\*\\*" +
        "|\\*\\*Registered Agent Tools\\*\\*" +
        "|You are (?:Vishnu|Brahma|Shiva|Saraswati|Ganesha|Shakti|Kartikeya),",
        Pattern.CASE_INSENSITIVE
    );

    public static final String SYSTEM_ROLE = "system";
    public static final String SYSTEM_USER = "system";
    public static final String SYSTEM_SESSION = "system";

}

