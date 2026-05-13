package com.arcclaw.core.reflex;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.message.UserMessages;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.domain.model.AgentCron;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CronStatus;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Fast-path reflex for listing a user's scheduled cron tasks.
 * Bypasses the LLM entirely for common "list my crons" queries.
 */
@ApplicationScoped
@ReflexPriority(500)
public class ListCronsReflex implements AgentReflex {

    @Inject
    AgentCronRepository cronRepository;

    @ConfigProperty(name = "arcclaw.cron.list-retention-hours", defaultValue = "24")
    int retentionHours;

    private static final Set<String> EXACT_MATCHES = Set.of(
        "/cron", "/crons", "/list crons", "list crons",
        "list tasks", "show cron", "my crons", "list my crons"
    );

    private static final List<String> PREFIX_MATCHES = List.of(
        "/jobs", "/schedule", "list my cron"
    );

    @Override
    public boolean matches(String input) {
        if (input == null) return false;
        String trimmed = input.trim().toLowerCase();
        return EXACT_MATCHES.contains(trimmed)
            || PREFIX_MATCHES.stream().anyMatch(trimmed::startsWith);
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        if (!WorkspaceScopeHolder.USER_ID.isBound()) {
            return ReflexResult.respond(UserMessages.LIST_CONTEXT_NOT_AVAILABLE, context);
        }
        String uid = WorkspaceScopeHolder.USER_ID.get();
        List<AgentCron> jobs = cronRepository.findByUserId(uid);
        if (jobs.isEmpty()) {
            return ReflexResult.respond(UserMessages.NO_SCHEDULE_FOUND, context);
        }

        Instant threshold = Instant.now().minus(retentionHours, ChronoUnit.HOURS);
        
        List<AgentCron> filtered = jobs.stream()
                .filter(j -> j.getStatus() == CronStatus.ACTIVE 
                          || j.getStatus() == CronStatus.AWAITING_APPROVAL
                          || j.getStatus() == CronStatus.RUNNING
                          || (j.getUpdatedAt() != null && j.getUpdatedAt().isAfter(threshold)))
                .toList();

        String output = filtered.stream()
                .map(AgentCron::toDisplayString)
                .collect(Collectors.joining("\n\n---\n\n"));

        int hidden = jobs.size() - filtered.size();
        if (hidden > 0) {
            output += String.format("\n\n_(%d older terminal tasks hidden)_", hidden);
        }
        
        return ReflexResult.respond(output, context);
    }

    @Override
    public String sanitize(String sessionId, String userId, String input) {
        return input;
    }
}
