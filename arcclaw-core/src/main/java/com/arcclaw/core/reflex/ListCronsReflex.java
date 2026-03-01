package com.arcclaw.core.reflex;

import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Fast-path reflex for listing a user's scheduled cron tasks.
 * Bypasses the LLM entirely for common "list my crons" queries.
 */
@ApplicationScoped
public class ListCronsReflex implements AgentReflex {

    @Inject
    AgentCronRepository cronRepository;

    @Override
    public boolean matches(String input) {
        if (input == null) return false;
        String trimmed = input.trim().toLowerCase();
        return trimmed.equals("/crons")
            || trimmed.equals("/cron")
            || trimmed.equals("/list crons")
            || trimmed.startsWith("/jobs")
            || trimmed.startsWith("/schedule")
            || trimmed.startsWith("list my cron");
    }

    @Override
    public String execute(String sessionId, String userId, String input) {
        if (!WorkspaceScopeHolder.USER_ID.isBound()) {
            return "Cannot list schedules — user context not available.";
        }
        String uid = WorkspaceScopeHolder.USER_ID.get();
        List<AgentCronEntity> jobs = cronRepository.findByUserId(uid);
        if (jobs.isEmpty()) {
            return "No scheduled tasks found.";
        }
        return jobs.stream()
                .map(AgentCronEntity::toDisplayString)
                .collect(Collectors.joining("\n---\n"));
    }

    @Override
    public String sanitize(String sessionId, String userId, String input) {
        return input;
    }
}
