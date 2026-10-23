package com.amsha.core.agent.loop;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.amsha.channel.api.ApprovalPrompt;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.core.util.AgentResultFormatter;
import com.amsha.core.util.DatabaseSafetyGuard;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.persistence.api.SessionCatalogStore;
import com.amsha.tool.api.AgentSentinels;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;

/**
 * Background scheduler that polls for sessions in AWAITING_RETRY state
 * and resumes them once their backoff duration has elapsed. §7.36
 */
@ApplicationScoped
public class RateLimitRetryScheduler {

    private static final Logger log = Logger.getLogger(RateLimitRetryScheduler.class);

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    AgentLoop agentLoop;

    @Inject
    ChannelPluginRegistry channelPluginRegistry;

    @Inject
    CronStorePort cronStore;

    @Inject
    CronRunStorePort cronRunStore;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    SessionCatalogStore sessionCatalogStore;

    @ConfigProperty(name = "amsha.retry.default-delay-ms", defaultValue = "30000")
    long defaultRetryDelayMs;

    private final AtomicLong backlogMaxAge = new AtomicLong(0);

    private volatile boolean shuttingDown = false;

    @PostConstruct
    void init() {
        meterRegistry.gauge("amsha.retry.backlog.max_age_seconds", backlogMaxAge);
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        this.shuttingDown = true;
    }

    @PreDestroy
    void destroy() {
        this.shuttingDown = true;
    }

    /**
     * Polls every 30 seconds for rate-limited sessions that are ready to resume.
     *
     * <p>
     * <strong>§5.9 (MUST):</strong> This method is transaction-free. Atomic state
     * transition is handled by {@link StatefulAgentStore#claimForRetry(Long)}.
     */
    @Scheduled(every = "5s", identity = "rate-limit-retry")
    @ActivateRequestContext
    public void processPendingRetries() {
        if (shuttingDown) {
            return;
        }

        try {
            List<AgentCheckpoint> awaiting = agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY);
            log.tracef(
                    "RateLimitRetryScheduler: scanning for ready sessions (found %d in AWAITING_RETRY)",
                    awaiting.size());

            if (awaiting.isEmpty()) {
                backlogMaxAge.set(0);
                return;
            }

            Instant now = Instant.now();
            long maxAgeSec = 0;

            for (AgentCheckpoint cp : awaiting) {
                if (shuttingDown) return;
                if (cp.getUpdatedAt() == null) continue;

                long ageSec = Duration.between(cp.getUpdatedAt(), now).toSeconds();
                maxAgeSec = Math.max(maxAgeSec, ageSec);

                long retryAfterMs = cp.getRetryAfterMs() != null ? cp.getRetryAfterMs() : defaultRetryDelayMs;
                Instant readyAt = cp.getUpdatedAt().plus(retryAfterMs, ChronoUnit.MILLIS);

                if (now.isAfter(readyAt)) {
                    log.infof(
                            "Resuming rate-limited session %s (retryAfterMs=%d, age=%ds)",
                            cp.getSessionId(), retryAfterMs, ageSec);

                    // 🟢 ATOMIC CLAIM: Transition to IN_PROGRESS in a fresh transaction inside the
                    // adapter
                    if (agentStore.claimForRetry(cp.getId())) {
                        try {
                            // Offload to background thread via AgentLoop.resume
                            if (!CronSessionStatusSync.syncRunning(cp.getSessionId(), cronStore)) {
                                agentStore.markFailed(cp.getId(), "Cron lease lost before retry resume");
                                continue;
                            }

                            AgentResult result = WorkspaceScopeHolder.call(
                                    cp.getSessionId(),
                                    () -> agentLoop.resume(cp.getSessionId(), cp.getAgentRole(), cp.getOriginalGoal()));
                            if (!CronSessionStatusSync.syncTerminal(
                                    cp.getSessionId(), result, cronStore, cronRunStore)) {
                                agentStore.markFailed(cp.getId(), "Cron lease lost during retry resume");
                            } else {
                                notifyRetryOutcome(cp, result, null);
                            }
                        } catch (Exception e) {
                            log.errorf(
                                    "Failed to resume rate-limited session %s:\n%s",
                                    cp.getSessionId(), LogUtil.trimmedStack(e));
                            CronSessionStatusSync.syncTerminal(
                                    cp.getSessionId(),
                                    AgentResult.failed(
                                            "Background retry failed: " + LogUtil.rootMessage(e),
                                            AgentResult.FailureCategory.SYSTEM_ERROR,
                                            List.of()),
                                    cronStore,
                                    cronRunStore);
                            agentStore.markFailed(cp.getId(), "Background retry failed: " + e.getMessage());
                            notifyRetryOutcome(cp, null, retryFailureMessage(e));
                        }
                    } else {
                        log.debugf("Session %s was already claimed by another thread, skipping", cp.getSessionId());
                    }
                } else {
                    long remaining = Duration.between(now, readyAt).toSeconds();
                    log.debugf("Session %s still in backoff (%ds remaining)", cp.getSessionId(), remaining);
                }
            }

            backlogMaxAge.set(maxAgeSec);
        } catch (RuntimeException e) {
            if (DatabaseSafetyGuard.checkDatabaseFailure(e)) return;
            String msg = e.getMessage() != null ? e.getMessage() : "";
            boolean isShutdown = shuttingDown || msg.contains("pool is closed") || msg.contains("database closed");
            if (!isShutdown) {
                log.error("RateLimitRetryScheduler: processPendingRetries failed", LogUtil.relevantThrowable(e));
            } else {
                log.infof(
                        "RateLimitRetryScheduler: database closed during processPendingRetries (app shutting down) - suppression intentional.");
                log.debugf("Full shutdown race details:\n%s", LogUtil.trimmedStack(e));
            }
        } catch (Exception e) {
            if (DatabaseSafetyGuard.checkDatabaseFailure(e)) return;
            log.error("RateLimitRetryScheduler: processPendingRetries failed", LogUtil.relevantThrowable(e));
        }
    }

    private void notifyRetryOutcome(AgentCheckpoint checkpoint, AgentResult result, String failureMessage) {
        if (checkpoint == null
                || checkpoint.getSessionId() == null
                || ResumeCronNotificationDispatcher.isCronSession(checkpoint.getSessionId())) {
            return;
        }

        try {
            SessionCatalogEntry session =
                    sessionCatalogStore.bySessionId(checkpoint.getSessionId()).orElse(null);
            ChannelType channelType = resolveNotificationChannelType(checkpoint, session);
            if (channelType != ChannelType.CLI && channelType != ChannelType.TELEGRAM) {
                return;
            }

            ChannelUser recipient = resolveNotificationRecipient(checkpoint, session, channelType);
            if (recipient == null) {
                log.warnf(
                        "Cannot route automatic retry result for session %s: channel recipient is unavailable",
                        checkpoint.getSessionId());
                return;
            }

            ChannelPlugin channelPlugin =
                    channelPluginRegistry.find(channelType.id()).orElse(null);
            if (channelPlugin == null) {
                log.warnf(
                        "Cannot route automatic retry result for session %s: no sender for channel %s",
                        checkpoint.getSessionId(), channelType.id());
                return;
            }
            sendRetryOutcome(channelPlugin.sender(), recipient, checkpoint, result, failureMessage);
        } catch (RuntimeException e) {
            log.warnf(
                    "Failed to notify channel about automatic retry for session %s: %s",
                    checkpoint.getSessionId(), LogUtil.rootMessage(e));
        }
    }

    private ChannelType resolveNotificationChannelType(AgentCheckpoint checkpoint, SessionCatalogEntry session) {
        String channelTypeId = session != null ? session.channelType() : checkpoint.getChannelType();
        ChannelType channelType = ChannelType.fromString(channelTypeId).orElse(null);
        if (channelType == null && checkpoint.getSessionId().startsWith(AgentSentinels.CLI_SESSION_PREFIX)) {
            return ChannelType.CLI;
        }
        return channelType;
    }

    private ChannelUser resolveNotificationRecipient(
            AgentCheckpoint checkpoint, SessionCatalogEntry session, ChannelType channelType) {
        String userId = session != null ? session.ownerKey() : checkpoint.getChannelPlatformId();
        String channelId = session != null ? session.channelId() : null;
        if (channelType == ChannelType.CLI) {
            if (userId == null || userId.isBlank()) {
                userId = ChannelType.CLI.id();
            }
            if (channelId == null || channelId.isBlank()) {
                channelId = userId;
            }
        }
        if (userId == null || userId.isBlank() || channelId == null || channelId.isBlank()) {
            return null;
        }
        return new ChannelUser(userId, channelId, "User", channelType);
    }

    private void sendRetryOutcome(
            ChannelSender sender,
            ChannelUser recipient,
            AgentCheckpoint checkpoint,
            AgentResult result,
            String failureMessage) {
        if (failureMessage != null) {
            sender.sendRaw(recipient, failureMessage);
            return;
        }
        if (result == null) {
            log.warnf("Automatic retry returned no result for session %s", checkpoint.getSessionId());
            return;
        }
        if (result instanceof AgentResult.AwaitingApproval awaitingApproval) {
            sender.send(
                    recipient,
                    List.of(new ApprovalPrompt(
                            awaitingApproval.toolName(),
                            awaitingApproval.displayParamsJson(),
                            checkpoint.getSessionId())));
            return;
        }

        String message = AgentResultFormatter.format(result);
        if (!(result instanceof AgentResult.AwaitingRetry)) {
            message = "🔄 Automatic retry result:\n\n" + message;
        }
        sender.sendRaw(recipient, message);
    }

    private static String retryFailureMessage(Exception exception) {
        String safeReason = AgentErrorMessageUtil.safeMessage(exception);
        return safeReason == null || safeReason.isBlank()
                ? "❌ Automatic retry failed. Check the application log for details."
                : "❌ Automatic retry failed: " + safeReason;
    }

    /**
     * Sweeps for checkpoints stuck in AWAITING_RETRY for > 30 minutes.
     * This handles cases where the scheduler might have missed an event or crashed.
     */
    @Scheduled(every = "5m", identity = "rate-limit-orphan-sweeper")
    public void sweepOrphanedRetries() {
        if (shuttingDown) {
            return;
        }

        try {
            List<AgentCheckpoint> awaiting = agentStore.findByStatus(CheckpointStatus.AWAITING_RETRY);
            Instant cutoff = Instant.now().minus(30, ChronoUnit.MINUTES);

            for (AgentCheckpoint cp : awaiting) {
                if (shuttingDown) return;
                if (cp.getUpdatedAt() != null && cp.getUpdatedAt().isBefore(cutoff)) {
                    log.errorf(
                            "Checkpoint %d is STALE in AWAITING_RETRY (updatedAt=%s). Marking as ORPHANED.",
                            cp.getId(), cp.getUpdatedAt());

                    agentStore.markOrphaned(
                            cp.getId(), "Abandoned after 30 minutes in AWAITING_RETRY (potential scheduler outage)");

                    meterRegistry.counter("amsha.retry.orphaned.count").increment();
                }
            }
        } catch (RuntimeException e) {
            if (DatabaseSafetyGuard.checkDatabaseFailure(e)) return;
            String msg = e.getMessage() != null ? e.getMessage() : "";
            boolean isShutdown = shuttingDown || msg.contains("pool is closed") || msg.contains("database closed");
            if (!isShutdown) {
                log.error("RateLimitRetryScheduler: sweepOrphanedRetries failed", LogUtil.relevantThrowable(e));
            } else {
                log.infof(
                        "RateLimitRetryScheduler: database closed during sweepOrphanedRetries (app shutting down) - suppression intentional.");
                log.debugf("Full shutdown race details:\n%s", LogUtil.trimmedStack(e));
            }
        } catch (Exception e) {
            if (DatabaseSafetyGuard.checkDatabaseFailure(e)) return;
            log.error("RateLimitRetryScheduler: sweepOrphanedRetries failed", LogUtil.relevantThrowable(e));
        }
    }
}
