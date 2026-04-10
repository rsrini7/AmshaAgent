package com.arcclaw.core.lifecycle;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.agent.loop.CliResultBridge;
import com.arcclaw.core.audit.AuditObserver;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.persistence.SqliteRetryTemplate;
import com.arcclaw.core.scope.WorkspaceScopeContext;
import com.arcclaw.core.scope.WorkspaceScoped;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.arc.Arc;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Lifecycle orchestrator for workspace session termination and disk management.
 * <p>
 * <strong>Responsibilities:</strong>
 * <ul>
 * <li><b>Termination:</b> Mark checkpoints FAILED, wipe workspace dir, destroy CDI scope.</li>
 * <li><b>Disk Management:</b> Physical cleanup of workspace directories.</li>
 * </ul>
 */
@ApplicationScoped
public class WorkspaceManager {

    private static final Logger log = Logger.getLogger(WorkspaceManager.class);

    @Inject EntityManager em;
    @Inject WorkspaceManager self;
    @Inject GovernanceStrategy governanceLayer;
    @Inject ChatMemoryStore chatMemoryStore;
    @Inject SessionHeartbeatTracker heartbeatTracker;
    @Inject StatefulAgentStore statefulAgentStore;
    @Inject SqliteRetryTemplate retryTemplate;
    @Inject AuditObserver auditObserver;
    @Inject CliResultBridge cliResultBridge;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    @ConfigProperty(name = "arcclaw.chat-memory.max-messages", defaultValue = "100")
    int maxMessages;

    // Session Termination

    /**
     * Terminate a workspace session completely.
     */
    public void terminateSession(String sessionId) {
        // F12: Safety guard to prevent termination during an active resume.
        // If the session is currently resuming its batch (e.g., calling tools),
        // we must not delete the workspace yet.
        if (statefulAgentStore.isBatchResuming(sessionId)) {
            log.warnf("Rejecting session termination for %s: session is currently RESUMING.", sessionId);
            throw new IllegalStateException("Cannot terminate session while it is actively resuming its batch");
        }

        log.infof("Terminating session: %s", sessionId);

        // 1. Remove from heartbeat tracking
        heartbeatTracker.removeHeartbeat(sessionId);

        // 1a. Clear budget memory
        governanceLayer.clearSession(sessionId);

        // 1b. Cleanup audit observer
        if (sessionId != null) {
            auditObserver.cleanupSession(sessionId);
        }

        // 2. Mark only truly live checkpoints FAILED — not terminal ones.
        //    doMarkCheckpointsFailedRequiresNew now excludes COMPLETED, ARCHIVED,
        //    and COMPROMISED in addition to FAILED. Previously, a cron job that
        //    finalized as COMPLETED would be immediately clobbered to FAILED here
        //    if the JPA transaction hadn't fully flushed before terminateSession ran.
        markCheckpointsFailed(sessionId);

        // 3. Wipe workspace directory
        wipeWorkspaceDir(sessionId);

        // 4. Destroy CDI scope
        try {
            var context = (WorkspaceScopeContext) Arc.container()
                    .getActiveContext(WorkspaceScoped.class);
            if (context != null) {
                context.destroySession(sessionId);
                log.infof("CDI scope destroyed for session=%s", sessionId);
            }
        } catch (Exception e) {
            log.warnf("Failed to destroy CDI scope for session=%s: %s",
                      sessionId, e.getMessage());
        }

        // 5. Clear any in-flight batch resume flag and bridge state for this session.
        cliResultBridge.cancel(sessionId);
        statefulAgentStore.clearBatchResuming(sessionId);
    }

    /**
     * Reset the session state while keeping the session record (WORM compliance).
     */
    public void resetSessionState(String sessionId) {
        log.infof("Soft-resetting session state for: %s", sessionId);

        wipeWorkspaceDir(sessionId);

        try {
            var context = (WorkspaceScopeContext) Arc.container()
                    .getActiveContext(WorkspaceScoped.class);
            if (context != null) {
                context.destroySession(sessionId);
            }
        } catch (Exception e) {
            log.warnf("CDI scope reset failed for session=%s: %s",
                      sessionId, e.getMessage());
        }
    }


    /**
     * Marks only genuinely live checkpoints as FAILED during session teardown.
     *
     * Excludes COMPLETED, ARCHIVED, and COMPROMISED in addition to FAILED.
     *
     * Root cause of the bug: AgentCronScheduler calls WorkspaceManager.terminateSession()
     * immediately after CronResultHandler marks the job COMPLETED. If the finalize
     * transaction (REQUIRES_NEW) hasn't fully flushed to SQLite before this query runs,
     * the checkpoint is still visible as IN_PROGRESS in the EntityManager cache and gets
     * incorrectly overwritten to FAILED — corrupting the audit trail and triggering
     * spurious failure notifications.
     *
     * By excluding all terminal statuses, we ensure only truly in-flight checkpoints
     * (IN_PROGRESS, AWAITING_APPROVAL, AWAITING_RETRY_APPROVAL) are clobbered.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doMarkCheckpointsFailedRequiresNew(String sessionId) {
        em.clear();
        List<AgentCheckpoint> checkpoints = em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid " +
                "  AND c.status NOT IN (:terminalStatuses)",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("terminalStatuses", CheckpointStatus.terminalStatuses())
                .getResultList();

        Instant now = Instant.now();
        for (var checkpoint : checkpoints) {
            checkpoint.setStatus(CheckpointStatus.FAILED);
            checkpoint.setFailedAt(now);
            checkpoint.setFailReason("Session terminated by WorkspaceManager");
        }
        if (!checkpoints.isEmpty()) {
            log.infof("Marked %d checkpoint(s) FAILED for session=%s",
                      checkpoints.size(), sessionId);
        }
    }

    void markCheckpointsFailed(String sessionId) {
        try {
            retryTemplate.executeVoid("markFailed-" + sessionId, 5, 50, () -> {
                self.doMarkCheckpointsFailedRequiresNew(sessionId);
            });
        } catch (IllegalStateException e) {
            // EntityManager already closed — JVM is shutting down, checkpoint
            // status update is best-effort only. Process is dying anyway.
            log.debugf("markCheckpointsFailed skipped for session=%s — EntityManager closed (shutdown in progress)", sessionId);
        } catch (Exception e) {
            log.errorf("Failed to mark checkpoints FAILED after retries for session=%s: %s",
                       sessionId, e.getMessage());
            log.debugf(e, "Full stacktrace for retry exhaustion in markCheckpointsFailed " +
                       "for session=%s", sessionId);
            throw (e instanceof RuntimeException re)
                ? re
                : new RuntimeException(
                    "Failed to mark checkpoints FAILED for session " + sessionId, e);
        }
    }

    private void wipeWorkspaceDir(String sessionId) {
        Path workspaceDir = Path.of(workspaceRootDir, sessionId);
        if (!Files.exists(workspaceDir)) return;
        try {
            Files.walkFileTree(workspaceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.infof("Workspace directory wiped: %s", workspaceDir);
        } catch (IOException e) {
            log.warnf("Failed to wipe workspace dir %s: %s",
                      workspaceDir, e.getMessage());
        }
    }
}
