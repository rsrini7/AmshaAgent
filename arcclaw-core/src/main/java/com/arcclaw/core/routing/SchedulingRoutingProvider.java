package com.arcclaw.core.routing;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.role.AgentRoleEnum;

@ApplicationScoped
public class SchedulingRoutingProvider implements IntentRoutingProvider {

    // NEGATION SAFETY: This pattern is intentionally specific to
    // time-delay signals (after X min, in X hours, remind me, etc.)
    // and does NOT match negations like "do not schedule" or
    // "cancel my reminder". If the pattern is ever broadened,
    // add a shouldSkip() guard via IntentRoutingProvider.shouldSkip()
    // default method to handle negations. See Phase 80-B B4 analysis.
    private static final Pattern SCHEDULING_GUARD_PATTERN = Pattern.compile(
        "(?i)^\\s*(after\\s+\\d|in\\s+\\d+\\s+(min|hour|day|sec)|tomorrow|" +
        "at\\s+\\d{1,2}[:.\\d]*\\s*(am|pm)?|schedule|remind(\\s+me)?|" +
        "every\\s+(day|week|hour)|once\\s+a\\s+(day|week)|later)\\b"
    );

    // FIX: Route free-text task-listing intents to coordinator (who has manageschedule)
    // instead of researcher/coder who do not.
    private static final Pattern LIST_TASKS_PATTERN = Pattern.compile(
        "(?i)^\\s*(list|show|display|get|view)\\s+(my\\s+)?(tasks?|crons?|jobs?|schedules?)\\b"
    );
    private static final Pattern MY_TASKS_PATTERN = Pattern.compile(
        "(?i)^\\s*my\\s+(tasks?|crons?|jobs?|scheduled\\s+tasks?)\\b"
    );

    private static final Pattern STANDALONE_CRON_PATTERN = Pattern.compile(
        "(?i)^/?crons?$"
    );

    @Override
    public List<Pattern> fastPathTriggers() {
        return List.of(
            SCHEDULING_GUARD_PATTERN,
            LIST_TASKS_PATTERN,       // "list tasks", "show my crons", "view jobs"
            MY_TASKS_PATTERN,         // "my tasks", "my crons"
            STANDALONE_CRON_PATTERN   // bare "cron" or "crons" (slash or plain)
        );
    }

    @Override
    public List<String> promptDirectives() {
        return List.of(
            "SCHEDULING IS ABSOLUTE PRIORITY — evaluated before any other rule: " +
            "If the user message contains ANY time-delay signal, you MUST route to 'coordinator'.",
            // FIX: Explicitly tell the LLM classifier that listing tasks belongs to coordinator.
            "Task/cron listing ('list tasks', 'show crons', 'my tasks') MUST route to 'coordinator' " +
            "because only coordinator has the manageschedule tool."
        );
    }

    @Override
    public Optional<String> fastPathRole() {
        return Optional.of(AgentRoleEnum.COORDINATOR.toScopedName());
    }

    @Override
    public int priority() {
        return 10;
    }
}