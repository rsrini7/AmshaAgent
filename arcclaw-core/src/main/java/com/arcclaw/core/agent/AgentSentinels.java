package com.arcclaw.core.agent;

import java.util.regex.Pattern;

/**
 * Centered sentinel strings for specialized agent logic, approval flows,
 * and state-machine transitions.
 */
public final class AgentSentinels {
    private AgentSentinels() {}

    /** User refused the tool execution. matches text in DenyReflex. */
    public static final String DENY_SENTINEL = "Human denied the tool execution.";

    /** Verbatim string currently inlined in ChatMemoryWriter for tool approvals. */
    public static final String GOAL_TOOL_APPROVED =
        "The tool execution has been approved and completed. Read the tool's output. If it is an error, tell me. Otherwise, provide the final answer to my original request.";

    /** Internal goal strings used when resuming a task. */
    public static final String GOAL_RESUME_APPROVED = "Resuming task after approval";
    public static final String GOAL_RESUME_DENIED   = "Resuming task after denial";
    public static final String GOAL_DENY_APPROVED   = "DENY_APPROVED_SENTINEL_V1";
    public static final String CRON_PREFIX = "CRON ";

    /** Per-tool decision: tool has not yet been acted on. §7.20 */
    public static final String TOOL_DECISION_PENDING  = "PENDING";

    /** Per-tool decision: tool was explicitly approved by the user. §7.20 */
    public static final String TOOL_DECISION_APPROVED = "APPROVED";

    /** Per-tool decision: tool was explicitly denied by the user. §7.20 */
    public static final String TOOL_DECISION_DENIED   = "DENIED";

    public static boolean isCronGoal(String goal) {
        return goal != null && goal.toUpperCase().startsWith(CRON_PREFIX);
    }

    public static final Pattern CONTEXT_RAG_ECHO_PATTERN = Pattern.compile(
         "^<rag_memory>[\\s\\S]*?</rag_memory>\\s*",
        Pattern.CASE_INSENSITIVE
    );

    // Compaction context echo guard 
    // The LLM occasionally pastes the injected "## Retrieved Context" compaction
    // header verbatim into its FINISH answer instead of writing a fresh response.
    // Observed: TELEGRAM-SEND text=[## Retrieved Context\nYou initiated a session...]
    // Fix: strip the header (and anything before the first real content line) so
    // the user sees the summary without the raw template heading.
    public static final Pattern CONTEXT_RETRIEVED_ECHO_PATTERN = Pattern.compile(
        "^##\\s*Retrieved Context[^\\n]*\\n+(?:<context>[\\s\\S]*?</context>\\s*)?",
        Pattern.CASE_INSENSITIVE
    );

}

