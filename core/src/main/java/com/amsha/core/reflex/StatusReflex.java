package com.amsha.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.message.UserMessages;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CompactionStorePort;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@ReflexPriority(500)
public class StatusReflex implements AgentReflex {

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    CompactionStorePort compactionSummaryRepository;

    @Inject
    GovernanceStrategy governanceStrategy;

    @ConfigProperty(name = "amsha.llm.provider", defaultValue = "openai")
    String provider = "openai";

    @ConfigProperty(name = "amsha.llm.model")
    Optional<String> modelName = Optional.empty();

    @Override
    public boolean matches(String input) {
        if (input == null) return false;
        String trimmed = input.trim().toLowerCase(Locale.ROOT);
        return trimmed.equals("/status")
                || trimmed.equals("/token")
                || trimmed.equals("/tokens")
                || trimmed.equals("/usage")
                || trimmed.equals("/stats")
                || trimmed.equals("/model");
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {

        List<AgentCheckpoint> checkpoints = new ArrayList<>();

        // Use AgentRoleEnum.values() - includes ALL built-in roles (ARCHITECT,
        // SYNTHESIZER, etc). A hardcoded list would silently miss new roles.
        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            statefulAgentStore.load(sessionId, role.toScopedName()).ifPresent(checkpoints::add);
        }

        String status = resolveStatus(checkpoints);

        double budgetUsd = governanceStrategy.getBudgetUsd();
        double cost = governanceStrategy.getSessionCost(sessionId);
        double pct = budgetUsd > 0 ? (cost / budgetUsd) * 100 : 0;
        int inputTokens = governanceStrategy.getSessionInputTokens(sessionId);
        int outputTokens = governanceStrategy.getSessionOutputTokens(sessionId);
        int totalTokens = inputTokens + outputTokens;

        String memoryLine = compactionSummaryRepository
                .findLatestBySessionId(sessionId)
                .map(s ->
                        String.format("Compacted (msgs summarized: %d) | Active window running", s.messagesCompacted()))
                .orElse("Not compacted - session within active window");

        String shortId = sessionId.length() > 8 ? sessionId.substring(sessionId.length() - 8) : sessionId;
        String prov = provider != null ? provider : "openai";
        String modelDisplay =
                (modelName != null && modelName.isPresent() && !modelName.get().isBlank())
                        ? prov + "/" + modelName.get()
                        : prov;

        String output = UserMessages.statusMessage(
                status,
                modelDisplay,
                cost,
                budgetUsd,
                pct,
                inputTokens,
                outputTokens,
                totalTokens,
                memoryLine,
                shortId);

        return ReflexResult.respond(output, context);
    }

    /**
     * Priority-ordered status resolution:
     *
     * 1. COMPROMISED  - security violation; surfaces immediately regardless of sort
     * 2. AWAITING_APPROVAL - human in the loop; must not be hidden
     * 3. IN_PROGRESS  - active execution
     * 4. Newest-updated checkpoint status (fallback sort)
     * 5. NOT_STARTED  - no checkpoints exist for this session
     *
     * NOT_STARTED is used (not a magic "NO_ACTIVE_STATE" string) to stay
     * consistent with AgentSessionService.getStatus() REST endpoint which
     * also returns CheckpointStatus.NOT_STARTED for the same condition.
     */
    private String resolveStatus(List<AgentCheckpoint> checkpoints) {
        if (checkpoints.isEmpty()) {
            return CheckpointStatus.NOT_STARTED.name();
        }

        // Priority 1 - security violation must always win
        for (AgentCheckpoint cp : checkpoints) {
            if (cp.getStatus() == CheckpointStatus.COMPROMISED) {
                return CheckpointStatus.COMPROMISED.name();
            }
        }

        // Priority 2 & 3 - active states trump terminal states
        for (AgentCheckpoint cp : checkpoints) {
            CheckpointStatus s = cp.getStatus();
            if (s == CheckpointStatus.RESUMING) {
                // Ignore stale resuming checkpoints older than 5 minutes
                if (cp.getUpdatedAt() != null
                        && Duration.between(cp.getUpdatedAt(), Instant.now()).toMinutes() > 5) {
                    continue;
                }
                return s.name();
            }
            if (s == CheckpointStatus.AWAITING_APPROVAL || s == CheckpointStatus.IN_PROGRESS) {
                return s.name();
            }
        }

        // Priority 4 - most recently updated terminal checkpoint
        checkpoints.sort((c1, c2) -> {
            if (c1.getUpdatedAt() == null) return 1;
            if (c2.getUpdatedAt() == null) return -1;
            return c2.getUpdatedAt().compareTo(c1.getUpdatedAt());
        });
        return checkpoints.getFirst().getStatus().name();
    }
}
