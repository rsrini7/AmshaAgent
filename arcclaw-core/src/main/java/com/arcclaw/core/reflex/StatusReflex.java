package com.arcclaw.core.reflex;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.governance.GovernanceLayer;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.CompactionSummaryRepository;
import com.arcclaw.core.state.StatefulAgentStore;

@ApplicationScoped
public class StatusReflex implements AgentReflex {

    @Inject StatefulAgentStore statefulAgentStore;
    @Inject CompactionSummaryRepository compactionSummaryRepository;
    @Inject GovernanceLayer governanceLayer;

    @Override
    public boolean matches(String input) {
        return input != null && input.trim().equalsIgnoreCase("/status");
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId,
                                String input, MessageContext context) {

        List<AgentCheckpoint> checkpoints = new ArrayList<>();

        // Use AgentRoleEnum.values() — includes ALL built-in roles (ARCHITECT,
        // SYNTHESIZER, etc). A hardcoded list would silently miss new roles.
        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            statefulAgentStore.load(sessionId, role.toScopedName())
                              .ifPresent(checkpoints::add);
        }

        String status = resolveStatus(checkpoints);

        double budgetUsd    = governanceLayer.getBudgetUsd();
        double cost         = governanceLayer.getSessionCost(sessionId);
        double pct          = budgetUsd > 0 ? (cost / budgetUsd) * 100 : 0;
        int    inputTokens  = governanceLayer.getSessionInputTokens(sessionId);
        int    outputTokens = governanceLayer.getSessionOutputTokens(sessionId);
        int    totalTokens  = inputTokens + outputTokens;

        String memoryLine = compactionSummaryRepository
            .findLatestBySessionId(sessionId)
            .map(s -> String.format("Compacted (msgs summarized: %d) | Active window running",
                s.getMessagesCompacted()))
            .orElse("Not compacted — session within active window");

        String shortId = sessionId.length() > 8
            ? sessionId.substring(sessionId.length() - 8) : sessionId;

        String output = String.format(
            "📊 *Status:* %s\n" +
            "💰 *Cost:* $%.4f / $%.2f (%.1f%%)\n" +
            "🔢 *Tokens:* %d prompt + %d completion = %d total\n" +
            "🧠 *Memory:* %s\n" +
            "⏱️ *Session:* %s",
            status, cost, budgetUsd, pct,
            inputTokens, outputTokens, totalTokens,
            memoryLine, shortId);

        return ReflexResult.respond(output, context);
    }

    /**
     * Priority-ordered status resolution:
     *
     * 1. COMPROMISED  — security violation; surfaces immediately regardless of sort
     * 2. AWAITING_APPROVAL — human in the loop; must not be hidden
     * 3. IN_PROGRESS  — active execution
     * 4. Newest-updated checkpoint status (fallback sort)
     * 5. NOT_STARTED  — no checkpoints exist for this session
     *
     * NOT_STARTED is used (not a magic "NO_ACTIVE_STATE" string) to stay
     * consistent with AgentSessionService.getStatus() REST endpoint which
     * also returns CheckpointStatus.NOT_STARTED for the same condition.
     */
    private String resolveStatus(List<AgentCheckpoint> checkpoints) {
        if (checkpoints.isEmpty()) {
            return CheckpointStatus.NOT_STARTED.name();
        }

        // Priority 1 — security violation must always win
        for (AgentCheckpoint cp : checkpoints) {
            if (cp.getStatus() == CheckpointStatus.COMPROMISED) {
                return CheckpointStatus.COMPROMISED.name();
            }
        }

        // Priority 2 & 3 — active states trump terminal states
        for (AgentCheckpoint cp : checkpoints) {
            CheckpointStatus s = cp.getStatus();
            if (s == CheckpointStatus.AWAITING_APPROVAL || s == CheckpointStatus.IN_PROGRESS) {
                return s.name();
            }
        }

        // Priority 4 — most recently updated terminal checkpoint
        checkpoints.sort((c1, c2) -> {
            if (c1.getUpdatedAt() == null) return 1;
            if (c2.getUpdatedAt() == null) return -1;
            return c2.getUpdatedAt().compareTo(c1.getUpdatedAt());
        });
        return checkpoints.getFirst().getStatus().name();
    }
}