package com.amsha.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCron;
import com.amsha.core.domain.model.AgentCronRecord;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.BatchDecisionUtil;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.tool.api.AgentSentinels;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fast-path reflex for listing all pending approvals visible to the current user.
 */
@ApplicationScoped
@ReflexPriority(500)
public class ListApprovalsReflex implements AgentReflex {

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    CronStorePort cronStore;

    @Override
    public boolean matches(String input) {
        if (input == null) return false;
        String t = input.trim().toLowerCase(Locale.ROOT);
        return t.equals("/approvals")
                || t.equals("/approval")
                || t.equals("/pending")
                || t.equals("/pending approvals")
                || t.equals("list approvals")
                || t.equals("show approvals")
                || t.equals("my approvals");
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        if (userId == null || userId.isBlank()) {
            return ReflexResult.respond("Cannot resolve user identity for approvals.", context);
        }

        List<String> lines = new ArrayList<>();

        List<AgentCheckpoint> pendingSessions = heartbeatTracker.activeSessionsByOwner(userId).keySet().stream()
                .filter(session -> !session.startsWith(AgentSentinels.CRON_SESSION_PREFIX))
                .map(statefulAgentStore::findLatestAwaitingApproval)
                .flatMap(opt -> opt.stream())
                .filter(cp -> cp.getStatus() == CheckpointStatus.AWAITING_APPROVAL)
                .sorted(Comparator.comparing(
                                AgentCheckpoint::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .toList();

        if (!pendingSessions.isEmpty()) {
            lines.add("**Session approvals**");
            for (AgentCheckpoint cp : pendingSessions) {
                lines.add(formatSessionApproval(cp));
            }
        }

        List<AgentCron> pendingCrons = cronStore.findAwaitingApprovalByUserId(userId).stream()
                .map(AgentCronRecord::fromDto)
                .collect(Collectors.toList());
        if (!pendingCrons.isEmpty()) {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add("**Cron approvals**");
            for (AgentCron cron : pendingCrons) {
                lines.add(formatCronApproval(cron));
            }
        }

        if (lines.isEmpty()) {
            return ReflexResult.respond("No pending approvals found.", context);
        }

        lines.add("");
        lines.add("Use `/approve <sessionId>` or `/deny <sessionId>` for a session.");
        lines.add("Use `/approve <cronId>` or `/deny <cronId>` for a cron job.");
        lines.add("If a session is a parallel batch, approve or deny the specific tool from that same session.");

        return ReflexResult.respond(String.join("\n", lines).strip(), context);
    }

    private String formatSessionApproval(AgentCheckpoint cp) {
        String tool = cp.getPendingToolName() != null ? cp.getPendingToolName() : "unknown-tool";
        String goal = cp.getOriginalGoal() != null ? cp.getOriginalGoal() : "(no goal)";
        String role = cp.getAgentRole() != null ? cp.getAgentRole() : "unknown-role";
        String when = formatInstant(cp.getUpdatedAt());
        String batchHint = "";
        if (cp.hasBatchToolDecisions()) {
            List<String> pendingTools = BatchDecisionUtil.pendingToolNames(cp, new ObjectMapper());
            if (!pendingTools.isEmpty()) {
                batchHint = "  Batch tools: " + String.join(", ", pendingTools)
                        + "\n  Approve all: `/approve " + cp.getSessionId() + "`"
                        + "\n  Deny all: `/deny " + cp.getSessionId() + "`";
            }
        }
        return String.format(
                        "• `%s` [%s]\n  Goal: %s\n  Pending: %s\n  Updated: %s",
                        cp.getSessionId(), role, goal, tool, when)
                + (batchHint.isBlank() ? "" : "\n" + batchHint);
    }

    private String formatCronApproval(AgentCron cron) {
        String when = cron.getUpdatedAt() != null ? cron.getUpdatedAt().toString() : "unknown";
        return String.format(
                "• `%s` [%s]\n  Goal: %s\n  Status: %s\n  Updated: %s\n  Approve: `/approve %s` or `/deny %s`",
                cron.getId(),
                cron.getExecutionRole() != null ? cron.getExecutionRole() : "coordinator",
                cron.getGoal() != null ? cron.getGoal() : "(no goal)",
                cron.getStatus() != null ? cron.getStatus().name() : "UNKNOWN",
                when,
                cron.getId(),
                cron.getId());
    }

    private String formatInstant(Instant instant) {
        return instant != null ? instant.toString() : "unknown";
    }

    @Override
    public String sanitize(String sessionId, String userId, String input) {
        return input;
    }
}
