package com.amsha.core.lifecycle;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.common.util.LogUtil;
import com.amsha.common.util.PathSanitizer;
import com.amsha.core.agent.loop.CliResultBridge;
import com.amsha.core.agent.loop.ToolInvocationGuard;
import com.amsha.core.audit.AuditObserver;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.hitl.ApprovalLeaseStore;
import com.amsha.core.scope.WorkspaceScopeContext;
import com.amsha.core.scope.WorkspaceScoped;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.tool.api.AgentSentinels;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Arc;

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

    /** TTL for halted session entries — auto-evicted after this duration. */
    private static final long HALTED_SESSION_TTL_MS = 5 * 60 * 1000L; // 5 minutes

    /** Tracks sessions that have been halted — agent loops check this before each iteration. */
    private static final ConcurrentHashMap<String, Long> HALTED_SESSIONS = new ConcurrentHashMap<>();

    /** Returns true if the session has been halted and the entry hasn't expired. */
    public static boolean isSessionHalted(String sessionId) {
        if (sessionId == null) return false;
        Long haltedAt = HALTED_SESSIONS.get(sessionId);
        if (haltedAt != null) {
            if (System.currentTimeMillis() - haltedAt > HALTED_SESSION_TTL_MS) {
                HALTED_SESSIONS.remove(sessionId);
                return false;
            }
            return true;
        }
        int delegateIdx = sessionId.indexOf(AgentSentinels.DELEGATE_SESSION_SENTINEL);
        if (delegateIdx > 0) {
            String parentSessionId = sessionId.substring(0, delegateIdx);
            return isSessionHalted(parentSessionId);
        }
        return false;
    }

    /** Returns true if any session has been halted (for interrupting blocking waits). */
    public static boolean isAnySessionHalted() {
        long now = System.currentTimeMillis();
        return HALTED_SESSIONS.entrySet().stream().anyMatch(e -> now - e.getValue() <= HALTED_SESSION_TTL_MS);
    }

    /** Clears the halted flag for a session (used on fresh start). */
    public static void clearHalted(String sessionId) {
        HALTED_SESSIONS.remove(sessionId);
    }

    /**
     * Marks a session as halted so that subsequent submissions are rejected
     * immediately by PreflightState. Unlike terminateSession(), this is a
     * lightweight static call suitable for use from state-machine internals
     * (e.g. when the global iteration budget is exhausted).
     */
    public static void haltSession(String sessionId) {
        if (sessionId == null) return;
        HALTED_SESSIONS.put(sessionId, System.currentTimeMillis());
        log.infof("[Halt] Session %s marked as halted (budget exceeded)", sessionId);
    }

    @Inject
    WorkspaceManager self;

    @Inject
    GovernanceStrategy governanceLayer;

    @Inject
    ChatMemoryStore chatMemoryStore;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    CheckpointStorePort checkpointStore;

    @Inject
    AuditObserver auditObserver;

    @Inject
    CliResultBridge cliResultBridge;

    @Inject
    ToolInvocationGuard toolInvocationGuard;

    @Inject
    ApprovalLeaseStore approvalLeaseStore;

    @Inject
    SessionTestLabelStore sessionTestLabelStore;

    @ConfigProperty(name = "amsha.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    @ConfigProperty(name = "amsha.chat-memory.max-messages", defaultValue = "20")
    int maxMessages;

    // Session Termination

    /**
     * Terminate a workspace session completely.
     */
    public void terminateSession(String sessionId) {
        // Mark session as halted FIRST — agent loop will check this before each iteration
        HALTED_SESSIONS.put(sessionId, System.currentTimeMillis());
        log.infof("[Halt] Session %s marked as halted — agent loop will stop at next iteration check", sessionId);

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

        // 1a. Clear budget and dedup memory
        governanceLayer.clearSession(sessionId);
        toolInvocationGuard.clearSession(sessionId);
        approvalLeaseStore.clearSession(sessionId);
        sessionTestLabelStore.clearSession(sessionId);

        // 1b. Cleanup audit observer
        if (sessionId != null) {
            auditObserver.cleanupSession(sessionId);
        }

        // 2. Mark only truly live checkpoints FAILED - not terminal ones.
        //    doMarkCheckpointsFailedRequiresNew now excludes COMPLETED, ARCHIVED,
        //    and COMPROMISED in addition to FAILED. Previously, a cron job that
        //    finalized as COMPLETED would be immediately clobbered to FAILED here
        //    if the JPA transaction hadn't fully flushed before terminateSession ran.
        markCheckpointsFailed(sessionId);

        // 3. Wipe workspace directory
        wipeWorkspaceDir(sessionId);

        // 4. Destroy CDI scope
        try {
            var context = (WorkspaceScopeContext) Arc.container().getActiveContext(WorkspaceScoped.class);
            if (context != null) {
                context.destroySession(sessionId);
                log.infof("CDI scope destroyed for session=%s", sessionId);
            }
        } catch (Exception e) {
            log.warnf("Failed to destroy CDI scope for session=%s: %s", sessionId, LogUtil.rootMessage(e));
        }

        // 5. Clear any in-flight batch resume flag and bridge state for this session.
        cliResultBridge.cancel(sessionId);
        statefulAgentStore.clearBatchResuming(sessionId);
    }

    /**
     * Reset the session state while keeping the session record (WORM compliance).
     */
    public void resetSessionState(String sessionId) {
        if (sessionId != null && (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\"))) {
            throw new SecurityException("Potential path traversal detected in sessionId: " + sessionId);
        }
        log.infof("Soft-resetting session state for: %s", sessionId);

        wipeWorkspaceDir(sessionId);

        try {
            var context = (WorkspaceScopeContext) Arc.container().getActiveContext(WorkspaceScoped.class);
            if (context != null) {
                context.destroySession(sessionId);
                toolInvocationGuard.clearSession(sessionId);
            }
        } catch (Exception e) {
            log.warnf("CDI scope reset failed for session=%s: %s", sessionId, LogUtil.rootMessage(e));
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
     * the checkpoint may still appear as IN_PROGRESS due to a read-write race and gets
     * incorrectly overwritten to FAILED - corrupting the audit trail and triggering
     * spurious failure notifications.
     *
     * By excluding all terminal statuses, we ensure only truly in-flight checkpoints
     * (IN_PROGRESS, AWAITING_APPROVAL, AWAITING_RETRY_APPROVAL) are clobbered.
     *
     * Now delegates to CheckpointStorePort (amsha-persistence-sqlite via adapter).
     * The retry logic and SQLite-specific transaction handling is encapsulated there.
     */
    void markCheckpointsFailed(String sessionId) {
        try {
            checkpointStore.markCheckpointsFailed(sessionId);
        } catch (IllegalStateException e) {
            // Database connection pool already closed - JVM is shutting down, checkpoint
            // status update is best-effort only. Process is dying anyway.
            log.debugf(
                    "markCheckpointsFailed skipped for session=%s - database closed (shutdown in progress)", sessionId);
        } catch (Exception e) {
            log.errorf("Failed to mark checkpoints FAILED for session=%s: %s", sessionId, LogUtil.rootMessage(e));
            log.debugf(
                    "Full stacktrace for checkpoint failure marking for session=%s:\n%s",
                    sessionId, LogUtil.trimmedStack(e));
            throw (e instanceof RuntimeException re)
                    ? re
                    : new RuntimeException("Failed to mark checkpoints FAILED for session " + sessionId, e);
        }
    }

    private void wipeWorkspaceDir(String sessionId) {
        String safeName = PathSanitizer.sanitizeForFilesystem(sessionId);
        Path workspaceDir = Path.of(workspaceRootDir, safeName);
        if (!Files.exists(workspaceDir)) return;
        try {
            Files.walkFileTree(workspaceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.infof("Workspace directory wiped: %s", workspaceDir);
        } catch (IOException e) {
            log.warnf("Failed to wipe workspace dir %s: %s", workspaceDir, LogUtil.rootMessage(e));
        }
    }
}
