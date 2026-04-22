package com.arcclaw.core.routing;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.role.AgentRoleEnum;

@ApplicationScoped
public class SchedulingRoutingProvider implements IntentRoutingProvider {

    // NEGATION SAFETY §4.11: This pattern is non-anchored to allow natural phrasing 
    // (e.g. "please remind me"), but "schedule" is removed because it's too broad.
    // Time signals like "tomorrow" or "in 30 min" are specific enough.
    // Added 'schedule' and common typos to ensure robust fast-path capture.
    private static final Pattern SCHEDULING_GUARD_PATTERN = Pattern.compile(
        "(?i)\\b(after\\s+\\d|in\\s+(one|oen|two|three|four|five|ten|\\d+)\\s+(min|hour|day|sec|week)[a-z]*|tomorrow|" +
        "at\\s+\\d{1,2}[:.\\d]*\\s*(am|pm)?|remind(\\s+me)?|" +
        "every\\s+(day|week|hour)[a-z]*|once\\s+a\\s+(day|week)|later|" +
        "schedul?e|scheduel|schdeule|remind|reminder)\\b",
        Pattern.CASE_INSENSITIVE
    );

    // Filter out explicit negations to prevent fast-path false positives (§4.11)
    private static final Pattern SCHEDULING_NEGATION_PATTERN =
        Pattern.compile(
            "(?i)(don'?t|do not|not|never|stop|cancel|no longer)"
            + "\\s{0,10}(want to |need to |going to )?"
            + "(schedule|remind|set.{0,15}reminder|schedul?e|scheduel|schdeule)",
            Pattern.CASE_INSENSITIVE
        );

    // FAST-PATH for listing tasks — still anchored as these are command-like
    private static final Pattern LIST_TASKS_PATTERN = Pattern.compile(
        "(?i)^\\s*(list|show|display|get|view)\\s+(my\\s+)?(tasks?|crons?|jobs?|schedules?|reminders?)\\b"
    );
    private static final Pattern MY_TASKS_PATTERN = Pattern.compile(
        "(?i)^\\s*my\\s+(tasks?|crons?|jobs?|scheduled\\s+tasks?|reminders?)\\b"
    );

    private static final Pattern STANDALONE_CRON_PATTERN = Pattern.compile(
        "(?i)^/?(crons?|schedules?|tasks?|reminders?)$"
    );

    // FAST-PATH for hybrid intents (e.g. "remind me to fetch") §7.36
    private static final Pattern SCHEDULE_RESEARCH_ACTION_PATTERN = Pattern.compile(
        "(?i)^(remind me|schedule to|scheduel to|schdeule to|remind me to|schedule for)\\s+(fetch|get|search|find|check|view|describe|lookup)\\b.*"
    );

    @Override
    public List<Pattern> fastPathTriggers() {
        return List.of(
            SCHEDULING_GUARD_PATTERN,
            LIST_TASKS_PATTERN,       
            MY_TASKS_PATTERN,         
            STANDALONE_CRON_PATTERN,
            SCHEDULE_RESEARCH_ACTION_PATTERN
        );
    }

    @Override
    public boolean shouldSkip(String userMessage) {
        return SCHEDULING_NEGATION_PATTERN.matcher(userMessage).find();
    }

    @Override
    public List<String> promptDirectives() {
        return List.of(
            "SCHEDULING IS ABSOLUTE PRIORITY — evaluated before any other rule: " +
            "If the user message contains ANY time-delay signal, you MUST route to 'coordinator'.",
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