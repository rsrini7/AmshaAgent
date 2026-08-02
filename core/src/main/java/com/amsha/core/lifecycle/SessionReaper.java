package com.amsha.core.lifecycle;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.hitl.ApprovalDecision;
import com.amsha.channel.api.hitl.ApprovalDecisionEvent;
import com.amsha.channel.api.hitl.ApprovalOutcome;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.loop.CliResultBridge;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.audit.SessionAuditSequencer;
import com.amsha.core.compaction.CompactionInFlightFlag;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCronRecord;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.state.ToolDecision;
import com.amsha.core.util.BatchDecisionUtil;
import com.amsha.core.util.CronScheduleUtil;
import com.amsha.core.util.DatabaseSafetyGuard;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.persistence.api.SessionCatalogStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

/**
 * Orchestrates scheduled session cleanup and zombie recovery.
 * <p>
 * Transaction pattern - mirrors AgentCronRepository WAL-safe split:
 * - Reads use NOT_SUPPORTED (no write lock acquired)
 * - Writes use REQUIRED (atomic micro-short transactions per row)
 * - Scheduled entry points have NO @Transactional (delegate to split methods)
 * This prevents SQLITE_BUSY / "cannot commit - no transaction is active" errors
 * caused by Narayana XA wrapping reads that SQLite has already auto-committed.
 */
@ApplicationScoped
public class SessionReaper {

    @Inject
    MeterRegistry meterRegistry;

    private static final Logger log = Logger.getLogger(SessionReaper.class);

    private volatile boolean shuttingDown = false;

    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @Inject
    SessionTestLabelStore sessionTestLabelStore;

    @Inject
    Event<ApprovalDecisionEvent> decisionEvents;

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SessionAuditSequencer auditSequencer;

    @Inject
    CheckpointStorePort checkpointStore;

    @Inject
    CronStorePort cronStore;

    @Inject
    CronRunStorePort cronRunStore;

    @Inject
    CompactionInFlightFlag compactionInFlight;

    @Inject
    AgentSseService agentSseService;

    @Inject
    CliResultBridge cliResultBridge;

    @Inject
    AuditService auditService;

    @Inject
    SessionCatalogStore sessionCatalogStore;

    @ConfigProperty(name = "amsha.workspace.session-ttl-minutes", defaultValue = "30")
    long sessionTtlMinutes;

    @ConfigProperty(name = "amsha.hitl.timeout-hours", defaultValue = "24")
    long hitlTimeoutHours;

    @ConfigProperty(name = "amsha.chat-memory.retention-days", defaultValue = "30")
    long retentionDays;

    @ConfigProperty(name = "amsha.session-catalog.archive-after-days", defaultValue = "90")
    long archiveAfterDays;

    void onShutdown(@Observes ShutdownEvent event) {
        shuttingDown = true;
    }

    /**
     * On application startup, find any IN_PROGRESS or AWAITING_APPROVAL
     * checkpoints from a previous JVM crash and mark them as FAILED.
     *
     * NO @Transactional annotation - actual transactional boundaries and
     * SQLite retry loops are handled by the delegated methods within
     * CheckpointStorePort to prevent nested REQUIRES_NEW overhead.
     */
    public void onStartup(@Observes StartupEvent event) {
        try {
            // Note: SQLite BUSY retries are handled internally (transparently)
            // by CheckpointStorePort via SqliteRetryTemplate.
            // FIXED (§FIX 3): No nested REQUIRES_NEW. recoverZombiesAndMarkFailed handles
            // full tx.
            doRecoverZombies();
        } catch (Exception e) {
            log.errorf("Failed onStartup zombie recovery: %s", LogUtil.rootMessage(e));
            log.debug("Full stacktrace for onStartup zombie recovery failure", e);
        }
    }

    private void doRecoverZombies() {
        Instant inProgressCutoff = Instant.now().minus(Duration.ofMinutes(15));

        // Step 1: Mark IN_PROGRESS zombies FAILED
        List<CheckpointDto> failed =
                checkpointStore.recoverInProgressZombies(inProgressCutoff, "JVM crash recovery - orphaned at startup");
        if (!failed.isEmpty()) {
            log.warnf("Zombie recovery: found and failed %d orphaned IN_PROGRESS checkpoint(s).", failed.size());
            failed.forEach(dto -> {
                log.infof("  Marked FAILED: id=%d session=%s role=%s", dto.id(), dto.sessionId(), dto.agentRole());
                cleanupCronOnFailure(dto.sessionId(), "JVM crash recovery - orphaned at startup");
            });
        } else {
            log.info("Zombie recovery: no orphaned IN_PROGRESS checkpoints found.");
        }

        // Step 2: Auto-deny ALL stale AWAITING_APPROVAL on restart.
        // After restart, conversation context is stale — the LLM will be confused
        // by old approval failures. All channels (CLI, Telegram, UI) are denied.
        // Users can re-issue their request after restart.
        List<CheckpointDto> pendingApprovals = checkpointStore.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name());
        if (!pendingApprovals.isEmpty()) {
            log.infof(
                    "Zombie recovery: found %d AWAITING_APPROVAL checkpoint(s) — auto-denying all.",
                    pendingApprovals.size());
            for (var dto : pendingApprovals) {
                log.warnf(
                        "  Auto-denying stale approval: id=%d session=%s role=%s tool=%s",
                        dto.id(), dto.sessionId(), dto.agentRole(), dto.pendingToolName());
                checkpointStore.transitionStatus(dto.id(), CheckpointStatus.DENIED.name());

                // Notify all channels consistently
                if (ChannelType.CLI.id().equalsIgnoreCase(dto.channelType())) {
                    cliResultBridge.cancel(dto.sessionId());
                }
                // Telegram: fire decision event so buttons are edited to "expired"
                // UI: complete SSE stream so subscribers are notified
                decisionEvents.fireAsync(new ApprovalDecisionEvent(
                        dto.sessionId(),
                        dto.channelType(),
                        dto.channelPlatformId(),
                        dto.pendingToolName(),
                        ApprovalDecision.REJECT,
                        ApprovalOutcome.EXPIRED));
                agentSseService.complete(dto.sessionId());
                cleanupCronOnFailure(dto.sessionId(), "JVM crash recovery - orphaned stale approval");
            }
        } else {
            log.info("Zombie recovery: no orphaned AWAITING_APPROVAL checkpoints found.");
        }
    }

    // GC SWEEP - terminates expired sessions

    /**
     * Scheduled GC sweep - terminates sessions whose heartbeat exceeds the TTL.
     * Skips sessions that are AWAITING_APPROVAL (handled by
     * sweepTimedOutApprovals).
     * NO @Transactional - delegates to split read/write methods.
     */
    @Scheduled(every = "${amsha.workspace.gc-interval:5m}", delayed = "PT90S")
    public void gcSweep() {
        if (shuttingDown) return;
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(sessionTtlMinutes));
        if (sessionTestLabelStore != null) {
            sessionTestLabelStore.reapExpired(cutoff);
        }
        if (heartbeatTracker.activeSessionCount() == 0) return;

        try {
            log.debugf("GC sweep: checking sessions against TTL=%dm (cutoff=%s)", sessionTtlMinutes, cutoff);

            Set<String> awaitingApprovalIds = findAwaitingApprovalIds();

            heartbeatTracker.forEachExpired(cutoff, sessionId -> {
                if (awaitingApprovalIds.contains(sessionId)) {
                    log.debugf("GC sweep: skipping session=%s (AWAITING_APPROVAL)", sessionId);
                    return;
                }
                if (agentStore.isBatchResuming(sessionId)) {
                    log.infof("GC sweep: skipping session=%s (RESUMING)", sessionId);
                    return;
                }
                log.warnf("GC sweep: session=%s expired", sessionId);
                meterRegistry.counter("amsha.agent.orphaned.sweep").increment();
                cliResultBridge.cancel(sessionId);
                compactionInFlight.remove(sessionId);
                workspaceManager.terminateSession(sessionId);
                heartbeatTracker.removeHeartbeat(sessionId);
                auditSequencer.releaseStaleSession(sessionId);
            });
        } catch (RuntimeException e) {
            if (DatabaseSafetyGuard.checkDatabaseFailure(e)) return;
            String msg = e.getMessage() != null ? e.getMessage() : "";
            boolean isShutdown = shuttingDown || msg.contains("pool is closed") || msg.contains("database closed");
            if (!isShutdown) {
                log.error("SessionReaper: gcSweep failed", e);
            } else {
                log.infof(
                        "SessionReaper: database closed during gcSweep (app shutting down) - suppression intentional.");
                log.debugf("Full shutdown race details:\n%s", LogUtil.trimmedStack(e));
            }
        } catch (Exception e) {
            if (DatabaseSafetyGuard.checkDatabaseFailure(e)) return;
            log.error("SessionReaper: gcSweep failed", e);
        }
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    Set<String> findAwaitingApprovalIds() {
        return checkpointStore.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name()).stream()
                .map(CheckpointDto::sessionId)
                .collect(Collectors.toSet());
    }

    // APPROVAL TIMEOUT SWEEP - terminates stale AWAITING_APPROVAL sessions

    /**
     * Terminate sessions that have been AWAITING_APPROVAL for too long.
     * Marks checkpoint FAILED and notifies user via their channel.
     * NO @Transactional - delegates to split read/write methods.
     */
    @Scheduled(every = "${amsha.workspace.gc-interval:5m}")
    public void sweepTimedOutApprovals() {
        if (shuttingDown) return;

        try {
            Instant cutoff = Instant.now().minus(Duration.ofHours(hitlTimeoutHours));
            List<AgentCheckpoint> stale = agentStore.findStaleAwaitingApproval(cutoff);
            if (stale.isEmpty()) return;

            for (AgentCheckpoint cp : stale) {
                log.warnf(
                        "[HITL-GC] Session %s role %s has been awaiting approval for >%dh - auto-denying.",
                        cp.getSessionId(), cp.getAgentRole(), hitlTimeoutHours);
                meterRegistry
                        .counter("amsha.hitl.timeout.auto_deny", "role", cp.getAgentRole())
                        .increment();
                markApprovalTimedOut(cp);
            }
        } catch (RuntimeException e) {
            if (DatabaseSafetyGuard.checkDatabaseFailure(e)) return;
            String msg = e.getMessage() != null ? e.getMessage() : "";
            boolean isShutdown = shuttingDown || msg.contains("pool is closed") || msg.contains("database closed");
            if (!isShutdown) {
                log.error("SessionReaper: sweepTimedOutApprovals failed", e);
            } else {
                log.infof(
                        "SessionReaper: database closed during sweepTimedOutApprovals (app shutting down) - suppression intentional.");
                log.debugf("Full shutdown race details:\n%s", LogUtil.trimmedStack(e));
            }
        } catch (Exception e) {
            if (DatabaseSafetyGuard.checkDatabaseFailure(e)) return;
            log.error("SessionReaper: sweepTimedOutApprovals failed", e);
        }
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    void markApprovalTimedOut(AgentCheckpoint cp) {
        auditService.recordMandatory(
                cp.getSessionId(),
                "system",
                cp.getAgentRole(),
                SecurityEventType.APPROVAL_DENIED,
                "reason=timeout_hours=" + hitlTimeoutHours + " tool=" + cp.getPendingToolName());

        if (cp.hasBatchToolDecisions()) {
            BatchDecisionUtil.pendingToolNames(cp, objectMapper)
                    .forEach(toolName -> agentStore.updateBatchToolDecision(
                            cp.getSessionId(), cp.getAgentRole(), toolName, ToolDecision.DENIED.name()));
        }

        agentStore.markFailed(cp.getId(), "Approval timed out after " + hitlTimeoutHours + " hours.");

        // Notify channel
        decisionEvents.fireAsync(new ApprovalDecisionEvent(
                cp.getSessionId(),
                cp.getChannelType(),
                cp.getChannelPlatformId(),
                cp.getPendingToolName(),
                ApprovalDecision.REJECT,
                ApprovalOutcome.EXPIRED));

        // Clean up SSE
        agentSseService.complete(cp.getSessionId());

        compactionInFlight.remove(cp.getSessionId());
        workspaceManager.terminateSession(cp.getSessionId());
        heartbeatTracker.removeHeartbeat(cp.getSessionId());
        auditSequencer.releaseStaleSession(cp.getSessionId());

        cleanupCronOnFailure(cp.getSessionId(), "Approval timed out after " + hitlTimeoutHours + " hours.");
    }

    private void cleanupCronOnFailure(String sessionId, String reason) {
        if (sessionId != null && sessionId.startsWith("cron-")) {
            String sub = sessionId.substring("cron-".length());
            if (sub.length() >= 36) {
                String jobId = sub.substring(0, 36);
                String executionId = sub.substring(37);
                try {
                    cronRunStore.findById(executionId).ifPresent(existing -> {
                        CronRunDto updated = new CronRunDto(
                                existing.id(),
                                existing.cronId(),
                                existing.sessionId(),
                                "FAILED",
                                existing.startedAt(),
                                Instant.now(),
                                reason,
                                existing.iterationsUsed(),
                                existing.inputTokens(),
                                existing.outputTokens(),
                                existing.usdCost(),
                                existing.createdAt());
                        cronRunStore.save(updated);
                    });
                } catch (Exception e) {
                    log.warnf(
                            "Failed to update cron run status on failure for run %s: %s",
                            executionId, LogUtil.rootMessage(e));
                }

                try {
                    var dto = cronStore.findById(jobId).orElse(null);
                    if (dto != null) {
                        var cron = AgentCronRecord.fromDto(dto);
                        if (!cron.isRunOnce()
                                && cron.getCronExpression() != null
                                && !cron.getCronExpression().isBlank()) {
                            Instant next = CronScheduleUtil.computeNext(cron.getCronExpression(), cron.getTimezone());
                            cronStore.markFired(jobId, Instant.now(), next);
                            log.infof(
                                    "CRON-REAP-RETRY recurring cronId=%s failed/timed out (rescheduled for %s)",
                                    jobId, next);
                        } else {
                            cronStore.markFailed(jobId, reason);
                            log.warnf("CRON-REAP-FAILED one-shot cronId=%s failed/timed out", jobId);
                        }
                    }
                } catch (Exception e) {
                    log.warnf("Failed to update cron status on failure for job %s: %s", jobId, LogUtil.rootMessage(e));
                }
            }
        }
    }

    // CHAT HISTORY RETENTION SWEEP - purges chat_memory rows for old sessions

    /**
     * Purge ChatMemory rows where sessionId has no active/recent checkpoint.
     * This prevents the chat_memory table from growing unboundedly (§85-retention).
     */
    @Scheduled(every = "24h", delayed = "PT10M")
    public void purgeOldChatHistory() {
        if (shuttingDown) return;

        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        log.infof("Purging chat history older than %d days (cutoff=%s)", retentionDays, cutoff);

        try {
            checkpointStore.purgeOldChatHistory(cutoff);
        } catch (Exception e) {
            log.warnf("Failed to purge old chat history: %s", LogUtil.rootMessage(e));
        }
    }

    @Scheduled(every = "24h", delayed = "PT15M")
    public void archiveInactiveCatalogSessions() {
        if (shuttingDown) return;

        Instant cutoff = Instant.now().minus(Duration.ofDays(archiveAfterDays));
        log.infof("Archiving session catalog entries inactive since %s", cutoff);

        try {
            List<SessionCatalogEntry> inactive = sessionCatalogStore.listInactiveBefore(cutoff);
            for (var entry : inactive) {
                try {
                    sessionCatalogStore.archive(entry.sessionId());
                    log.debugf("Archived catalog session %s", entry.sessionId());
                } catch (Exception e) {
                    log.warnf("Failed to archive catalog session %s: %s", entry.sessionId(), LogUtil.rootMessage(e));
                }
            }
            log.infof("Catalog archive sweep: %d sessions archived", inactive.size());
        } catch (Exception e) {
            log.warnf("Failed to sweep catalog archive: %s", LogUtil.rootMessage(e));
        }
    }
}
