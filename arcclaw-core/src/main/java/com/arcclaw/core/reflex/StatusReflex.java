package com.arcclaw.core.reflex;

import com.arcclaw.core.governance.GovernanceLayer;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.CompactionSummaryRepository;
import com.arcclaw.core.state.StatefulAgentStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class StatusReflex implements AgentReflex {

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    CompactionSummaryRepository compactionSummaryRepository;

    @Inject
    GovernanceLayer governanceLayer;

    @Override
    public boolean matches(String input) {
        return input != null && input.trim().equalsIgnoreCase("/status");
    }

    @Override
    public String execute(String sessionId, String userId, String chatId, String input) {
        List<AgentCheckpoint> checkpoints = new ArrayList<>();
        
        for (String role : List.of("coordinator", "coder", "researcher", "debugger")) {
            statefulAgentStore.load(sessionId, role).ifPresent(checkpoints::add);
        }

        String status = "NO_ACTIVE_STATE";
        
        if (!checkpoints.isEmpty()) {
            // 1. Default to the most recently updated checkpoint's status
            checkpoints.sort((c1, c2) -> {
                if (c1.getUpdatedAt() == null) return 1;
                if (c2.getUpdatedAt() == null) return -1;
                return c2.getUpdatedAt().compareTo(c1.getUpdatedAt()); // Descending (newest first)
            });
            
            status = checkpoints.get(0).getStatus().name();

            // 2. Override: If ANY role is currently running or waiting, that is the session's true status
            for (AgentCheckpoint cp : checkpoints) {
                CheckpointStatus cpStatus = cp.getStatus();
                if (cpStatus == CheckpointStatus.AWAITING_APPROVAL || cpStatus == CheckpointStatus.IN_PROGRESS) {
                    status = cpStatus.name();
                    break;
                }
            }
        }

        double budgetUsd = governanceLayer.getBudgetUsd();
        double cost = governanceLayer.getSessionCost(sessionId);
        double pct = budgetUsd > 0 ? (cost / budgetUsd) * 100 : 0;
        int inputTokens = governanceLayer.getSessionInputTokens(sessionId);
        int outputTokens = governanceLayer.getSessionOutputTokens(sessionId);
        int totalTokens = inputTokens + outputTokens;

        // Use findLatestBySessionId for consistency
        String memoryLine = compactionSummaryRepository
            .findLatestBySessionId(sessionId)
            .map(s -> String.format("Compacted (msgs summarized: %d) | Active window running",
                s.messagesCompacted))
            .orElse("Not compacted — session within active window");

        String shortId = sessionId.length() > 8 ? sessionId.substring(sessionId.length() - 8) : sessionId;

        return String.format(
            "📊 *Status:* %s\n" +
            "💰 *Cost:* $%.4f / $%.2f (%.1f%%)\n" +
            "🔢 *Tokens:* %d prompt + %d completion = %d total\n" +
            "🧠 *Memory:* %s\n" +
            "⏱️ *Session:* %s",
            status, cost, budgetUsd, pct,
            inputTokens, outputTokens, totalTokens,
            memoryLine, shortId);
    }
}