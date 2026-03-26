package com.arcclaw.core.agent;

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
    public static final String CRON_PREFIX = "CRON ";

    public static boolean isCronGoal(String goal) {
        return goal != null && goal.toUpperCase().startsWith(CRON_PREFIX);
    }
}

