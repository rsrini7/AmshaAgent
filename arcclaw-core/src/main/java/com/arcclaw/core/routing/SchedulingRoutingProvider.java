package com.arcclaw.core.routing;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import com.arcclaw.core.agent.role.AgentRoleEnum;

@ApplicationScoped
public class SchedulingRoutingProvider implements IntentRoutingProvider {
    private static final Pattern SCHEDULING_GUARD_PATTERN = Pattern.compile(
        "(?i)\\b(after\\s+\\d|in\\s+\\d+\\s+(min|hour|day|sec)|tomorrow|" +
        "at\\s+\\d{1,2}[:.\\d]*\\s*(am|pm)?|schedule|remind(\\s+me)?|" +
        "every\\s+(day|week|hour)|once\\s+a\\s+(day|week)|later)\\b"
    );

    @Override
    public List<Pattern> fastPathTriggers() {
        return List.of(SCHEDULING_GUARD_PATTERN);
    }

    @Override
    public List<String> promptDirectives() {
        return List.of(
            "SCHEDULING IS ABSOLUTE PRIORITY — evaluated before any other rule: " +
            "If the user message contains ANY time-delay signal, you MUST route to 'coordinator'."
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
