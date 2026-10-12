package com.amsha.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.message.UserMessages;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.security.InputSanitizer;
import com.amsha.core.state.ActiveWork;
import com.amsha.core.state.SessionTree;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.llm.api.LlmConfigKeys;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CompactionStorePort;
import com.amsha.persistence.api.CronStatus;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.tool.api.AgentSentinels;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@ReflexPriority(500)
public class StatusReflex implements AgentReflex {

    /** Goals longer than this are truncated in the status line. */
    private static final int MAX_GOAL_LENGTH = ActiveWork.MAX_GOAL_LENGTH;

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    CompactionStorePort compactionSummaryRepository;

    @Inject
    CronStorePort cronStorePort;

    @Inject
    GovernanceStrategy governanceStrategy;

    @Inject
    @InputSanitizer.PII
    InputSanitizer piiSanitizer;

    @ConfigProperty(name = LlmConfigKeys.PROVIDER, defaultValue = ModelDefaults.PROVIDER_OPENAI)
    String provider = ModelDefaults.PROVIDER_OPENAI;

    @ConfigProperty(name = LlmConfigKeys.MODEL)
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

        statefulAgentStore.findLatestNonArchivedCheckpoint(sessionId).ifPresent(cp -> addCheckpoint(checkpoints, cp));
        statefulAgentStore.findActiveCheckpointsInSessionTree(sessionId).forEach(cp -> addCheckpoint(checkpoints, cp));

        statefulAgentStore
                .findLatestAwaitingApproval(sessionId)
                .or(() -> statefulAgentStore.findLatestAwaitingApprovalBySessionContaining(
                        sessionId + AgentSentinels.DELEGATE_SESSION_SENTINEL))
                .filter(cp -> SessionTree.contains(sessionId, cp.getSessionId()))
                .ifPresent(cp -> addCheckpoint(checkpoints, cp));

        boolean isResuming = statefulAgentStore.isBatchResuming(sessionId);
        String fallbackGoal = checkpoints.stream()
                .filter(this::isActiveCheckpoint)
                .map(AgentCheckpoint::getOriginalGoal)
                .filter(goal -> goal != null && !goal.isBlank())
                .findFirst()
                .orElse(null);
        ActiveWork resolved = ActiveWork.resolve(
                statefulAgentStore.getActiveWork(sessionId),
                statefulAgentStore.getActiveGoal(sessionId),
                statefulAgentStore.getActivePhase(sessionId),
                fallbackGoal);
        ActiveWork work = ActiveWork.of(sanitizeGoal(resolved.goal()), sanitizePhase(resolved.phase()));
        String status = resolveStatus(checkpoints, isResuming, work);

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
        String prov = provider != null ? provider : ModelDefaults.PROVIDER_OPENAI;
        String modelDisplay =
                (modelName != null && modelName.isPresent() && !modelName.get().isBlank())
                        ? prov + "/" + modelName.get()
                        : prov;

        String pendingRunStatus = null;
        if (userId != null && !userId.isBlank()) {
            pendingRunStatus = cronStorePort.findByUserId(userId).stream()
                    .filter(cron -> sessionId.equals(cron.sessionId()))
                    .map(cron -> cron.status())
                    .filter(cronStatus -> CronStatus.AWAITING_APPROVAL.name().equals(cronStatus)
                            || CronStatus.AWAITING_RETRY.name().equals(cronStatus))
                    .findFirst()
                    .orElse(null);
        }

        String statusDisplay = pendingRunStatus == null ? status : status + " (chat session)";
        String output = UserMessages.statusMessage(
                statusDisplay,
                modelDisplay,
                cost,
                budgetUsd,
                pct,
                inputTokens,
                outputTokens,
                totalTokens,
                memoryLine,
                shortId);

        if (pendingRunStatus != null) {
            output += "\n\n📅 *Scheduled run: " + pendingRunStatus + "* — check Pending Approvals to continue.";
        }

        return ReflexResult.respond(output, context);
    }

    /**
     * Priority-ordered status resolution:
     *
     * 1. COMPROMISED  - security violation; surfaces immediately regardless of sort
     * 2. AWAITING_APPROVAL - human in the loop; must not be hidden
     * 3. IN_PROGRESS  - active in-memory execution or running checkpoint
     * 4. Newest-updated checkpoint status (fallback sort)
     * 5. NOT_STARTED  - no checkpoints exist for this session
     *
     * NOT_STARTED is used (not a magic "NO_ACTIVE_STATE" string) to stay
     * consistent with AgentSessionService.getStatus() REST endpoint which
     * also returns CheckpointStatus.NOT_STARTED for the same condition.
     */
    private String resolveStatus(List<AgentCheckpoint> checkpoints) {
        return resolveStatus(checkpoints, false, ActiveWork.EMPTY);
    }

    private String resolveStatus(List<AgentCheckpoint> checkpoints, boolean isResuming, ActiveWork activeWork) {
        if (checkpoints.isEmpty() && !isResuming) {
            return CheckpointStatus.NOT_STARTED.name();
        }

        // Priority 1 - security violation must always win
        for (AgentCheckpoint cp : checkpoints) {
            if (cp.getStatus() == CheckpointStatus.COMPROMISED) {
                return CheckpointStatus.COMPROMISED.name();
            }
        }

        // Priority 2 - active human-in-the-loop approvals must not be hidden
        for (AgentCheckpoint cp : checkpoints) {
            if (cp.getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                return formatActiveStatus(CheckpointStatus.AWAITING_APPROVAL, workFor(cp, activeWork));
            }
            if (cp.getStatus() == CheckpointStatus.AWAITING_RETRY) {
                return formatActiveStatus(CheckpointStatus.AWAITING_RETRY, workFor(cp, activeWork));
            }
        }

        // Priority 3 - active in-memory execution trumps terminal checkpoint states
        if (isResuming) {
            return formatInProgressStatus(activeWork);
        }

        // Priority 3b - persisted resuming or in-progress checkpoints
        for (AgentCheckpoint cp : checkpoints) {
            CheckpointStatus s = cp.getStatus();
            if ((s == CheckpointStatus.RESUMING || s == CheckpointStatus.IN_PROGRESS)
                    && !StatefulAgentStore.isStaleActiveCheckpoint(cp)) {
                return formatActiveStatus(s, workFor(cp, activeWork));
            }
        }

        // Priority 4 - most recently updated terminal checkpoint
        return checkpoints.stream()
                .filter(cp -> cp.getStatus() != null
                        && cp.getStatus().isTerminal()
                        && !StatefulAgentStore.isActiveStatus(cp.getStatus()))
                .max((c1, c2) -> {
                    if (c1.getUpdatedAt() == null) return -1;
                    if (c2.getUpdatedAt() == null) return 1;
                    return c1.getUpdatedAt().compareTo(c2.getUpdatedAt());
                })
                .map(cp -> cp.getStatus().name())
                .orElse(CheckpointStatus.NOT_STARTED.name());
    }

    static String formatInProgressStatus(ActiveWork activeWork) {
        return formatActiveStatus(CheckpointStatus.IN_PROGRESS, activeWork);
    }

    private static String formatActiveStatus(CheckpointStatus status, ActiveWork activeWork) {
        if (activeWork == null || activeWork.isEmpty()) {
            return status.name();
        }
        return status.name() + " (" + activeWork.describe(MAX_GOAL_LENGTH) + ")";
    }

    private static void addCheckpoint(List<AgentCheckpoint> checkpoints, AgentCheckpoint candidate) {
        if (candidate == null) return;
        if (checkpoints.stream()
                .noneMatch(cp -> cp.getId() != null && cp.getId().equals(candidate.getId()))) {
            checkpoints.add(candidate);
        }
    }

    private boolean isActiveCheckpoint(AgentCheckpoint checkpoint) {
        return checkpoint != null
                && StatefulAgentStore.isActiveStatus(checkpoint.getStatus())
                && !StatefulAgentStore.isStaleActiveCheckpoint(checkpoint);
    }

    private String sanitizeGoal(String goal) {
        return piiSanitizer != null && goal != null ? piiSanitizer.sanitize(goal) : goal;
    }

    private String sanitizePhase(String phase) {
        if (phase == null || phase.isBlank()) return phase;
        // Phase labels are internally generated (e.g. "TOOL:read_file"), but sanitize
        // defensively in case a tool name leaks user-derived content.
        return piiSanitizer != null ? piiSanitizer.sanitize(phase) : phase;
    }

    /** Prefers the checkpoint's own goal; falls back to the live-work goal. */
    private ActiveWork workFor(AgentCheckpoint checkpoint, ActiveWork activeWork) {
        String goal = checkpoint.getOriginalGoal();
        return goal == null || goal.isBlank() ? activeWork : activeWork.withGoal(sanitizeGoal(goal));
    }
}
