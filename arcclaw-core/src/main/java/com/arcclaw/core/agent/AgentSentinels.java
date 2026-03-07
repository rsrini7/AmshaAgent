package com.arcclaw.core.agent;

/**
 * Centered sentinel strings for specialized agent logic, approval flows,
 * and state-machine transitions.
 */
public final class AgentSentinels {
    private AgentSentinels() {}

    /** User refused the tool execution. matches text in DenyReflex. */
    public static final String DENY_SENTINEL = "Human denied the tool execution.";

    /** Prefix used when resuming a task from a checkpoint. */
    public static final String RESUME_GOAL_PREFIX = "resuming task";
}
