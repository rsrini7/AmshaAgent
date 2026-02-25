package com.arcclaw.core.lifecycle;

import com.arcclaw.core.scope.WorkspaceScoped;
import com.arcclaw.core.scope.WorkspaceScopeContext;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifecycle orchestrator connecting CDI scope, state store, and workspace
 * management.
 * <p>
 * <strong>Responsibilities:</strong>
 * <ul>
 * <li><b>Heartbeats:</b> Track active session liveness via
 * {@link ConcurrentHashMap}</li>
 * <li><b>GC Sweep:</b> {@code @Scheduled} reaping of sessions exceeding
 * TTL</li>
 * <li><b>Zombie Recovery:</b> On startup, mark any orphaned IN_PROGRESS
 * checkpoints as FAILED</li>
 * <li><b>Termination:</b> Mark FAILED, wipe workspace dir, destroy CDI
 * scope</li>
 * </ul>
 * <p>
 * All shared state is guarded by {@code synchronized} per JEP 491 — safe for
 * virtual threads on JDK 25.
 */
@ApplicationScoped
public class WorkspaceManager {

    private static final Logger log = Logger.getLogger(WorkspaceManager.class);

    private volatile boolean shuttingDown = false;

    /** Active session heartbeats: sessionId → last heartbeat timestamp. */
    private final ConcurrentHashMap<String, Instant> heartbeats = new ConcurrentHashMap<>();

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    EntityManager em;

    @Inject
    com.arcclaw.core.governance.GovernanceLayer governanceLayer;

    @Inject
    ChatMemoryStore chatMemoryStore;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    @ConfigProperty(name = "arcclaw.workspace.session-ttl-minutes", defaultValue = "30")
    long sessionTtlMinutes;

    @ConfigProperty(name = "arcclaw.governance.approval-timeout-minutes", defaultValue = "60")
    long approvalTimeoutMinutes;

    void onShutdown(@Observes ShutdownEvent event) {
        shuttingDown = true;
    }

    // -------------------------------------------------------------------------
    // Heartbeat Management
    // -------------------------------------------------------------------------

    /**
     * Record a heartbeat for a session — proves the session is still alive.
     *
     * @param sessionId The workspace session ID.
     */
    public void recordHeartbeat(String sessionId) {
        heartbeats.put(sessionId, Instant.now());
        log.debugf("Heartbeat recorded for session=%s", sessionId);
    }

    /**
     * Remove a session from heartbeat tracking.
     */
    public void removeHeartbeat(String sessionId) {
        heartbeats.remove(sessionId);
    }

    /**
     * Get the number of tracked sessions (for testing/monitoring).
     */
    public int activeSessionCount() {
        return heartbeats.size();
    }

    /**
     * Clear all tracked sessions (for testing).
     */
    public void clearAll() {
        heartbeats.clear();
    }

    // -------------------------------------------------------------------------
    // Zombie Recovery (Startup)
    // -------------------------------------------------------------------------

    /**
     * On application startup, find any IN_PROGRESS checkpoints from a previous
     * JVM crash and mark them as FAILED.
     * <p>
     * This ensures no orphaned sessions linger with stale state.
     */
    @Transactional
    public void onStartup(@Observes StartupEvent event) {
        List<AgentCheckpoint> zombies = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.IN_PROGRESS)
                .getResultList();

        if (zombies.isEmpty()) {
            log.info("Zombie recovery: no orphaned IN_PROGRESS checkpoints found.");
            return;
        }

        log.warnf("Zombie recovery: found %d orphaned IN_PROGRESS checkpoint(s) — marking FAILED",
                zombies.size());
        Instant now = Instant.now();
        for (var checkpoint : zombies) {
            checkpoint.status = CheckpointStatus.FAILED;
            checkpoint.failedAt = now;
            checkpoint.failReason = "JVM crash recovery — session was IN_PROGRESS at startup";
            em.merge(checkpoint);
            log.infof("  Marked checkpoint id=%d (session=%s, role=%s) as FAILED",
                    checkpoint.id, checkpoint.sessionId, checkpoint.agentRole);
        }
    }

    // -------------------------------------------------------------------------
    // Garbage Collection (Scheduled)
    // -------------------------------------------------------------------------

    /**
     * Scheduled GC sweep — terminates sessions whose heartbeat exceeds the TTL.
     * <p>
     * Runs at the interval configured by {@code arcclaw.workspace.gc-interval}.
     */
    @Scheduled(every = "${arcclaw.workspace.gc-interval:5m}")
    void gcSweep() {
        if (heartbeats.isEmpty()) {
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofMinutes(sessionTtlMinutes));
        log.debugf("GC sweep: checking %d session(s) against TTL=%dm (cutoff=%s)",
                heartbeats.size(), sessionTtlMinutes, cutoff);

        heartbeats.forEach((sessionId, lastHeartbeat) -> {
            if (lastHeartbeat.isBefore(cutoff)) {
                // Step 6: Sprint 2B - Skip GC if session is AWAITING_APPROVAL
                boolean awaitingApproval = em.createQuery(
                        "SELECT COUNT(c) FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.status = :status",
                        Long.class)
                        .setParameter("sid", sessionId)
                        .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                        .getSingleResult() > 0;

                if (awaitingApproval) {
                    log.debugf("GC sweep: skipping session=%s (AWAITING_APPROVAL)", sessionId);
                    return;
                }

                log.warnf("GC sweep: session=%s expired (last heartbeat=%s, cutoff=%s)",
                        sessionId, lastHeartbeat, cutoff);
                terminateSession(sessionId);
            }
        });
    }

    /**
     * Terminate sessions that have been AWAITING_APPROVAL for too long.
     */
    @Scheduled(every = "${arcclaw.workspace.gc-interval:5m}")
    @Transactional
    public void sweepTimedOutApprovals() {
        if (shuttingDown) return;   // ← skip if JVM is shutting down

        var cutoff = java.time.LocalDateTime.now()
                .minus(java.time.Duration.ofMinutes(approvalTimeoutMinutes));
        List<AgentCheckpoint> timedOut = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status AND c.updatedAt < :cutoff",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("cutoff", cutoff)
                .getResultList();

        for (var cp : timedOut) {
            log.warnf("Approval timeout session=%s. Terminating.", cp.sessionId);
            terminateSession(cp.sessionId);
        }
    }

    // -------------------------------------------------------------------------
    // Session Termination
    // -------------------------------------------------------------------------

    /**
     * Terminate a workspace session completely.
     * <p>
     * Steps:
     * <ol>
     * <li>Remove from heartbeat tracking</li>
     * <li>Mark any DB checkpoints as FAILED</li>
     * <li>Wipe the physical workspace directory</li>
     * <li>Delete chat memory from persistence</li>
     * <li>Destroy the CDI scope (all @WorkspaceScoped beans)</li>
     * </ol>
     */
    public void terminateSession(String sessionId) {
        log.infof("Terminating session: %s", sessionId);

        // 1. Remove from heartbeat tracking
        heartbeats.remove(sessionId);

        // 1a. Clear budget memory
        governanceLayer.clearSession(sessionId);

        // 2. Mark checkpoints FAILED (in a transaction)
        markCheckpointsFailed(sessionId);

        // 3. Wipe workspace directory
        wipeWorkspaceDir(sessionId);

        // 3a. Delete chat memory
        try {
            chatMemoryStore.deleteMessages(sessionId);
            log.debugf("Chat memory deleted for session=%s", sessionId);
        } catch (Exception e) {
            log.warnf("Failed to delete chat memory for session=%s: %s", sessionId, e.getMessage());
        }

        // 4. Destroy CDI scope
        try {
            var context = (WorkspaceScopeContext) Arc.container()
                    .getActiveContext(WorkspaceScoped.class);
            if (context != null) {
                context.destroySession(sessionId);
                log.infof("CDI scope destroyed for session=%s", sessionId);
            }
        } catch (Exception e) {
            log.warnf("Failed to destroy CDI scope for session=%s: %s", sessionId, e.getMessage());
        }
    }

    @Transactional
    void markCheckpointsFailed(String sessionId) {
        List<AgentCheckpoint> checkpoints = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.status != :failed",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("failed", CheckpointStatus.FAILED)
                .getResultList();

        Instant now = Instant.now();
        for (var checkpoint : checkpoints) {
            checkpoint.status = CheckpointStatus.FAILED;
            checkpoint.failedAt = now;
            checkpoint.failReason = "Session terminated by WorkspaceManager";
            em.merge(checkpoint);
        }
        if (!checkpoints.isEmpty()) {
            log.infof("Marked %d checkpoint(s) FAILED for session=%s", checkpoints.size(), sessionId);
        }
    }

    private void wipeWorkspaceDir(String sessionId) {
        Path workspaceDir = Path.of(workspaceRootDir, sessionId);
        if (!Files.exists(workspaceDir)) {
            return;
        }
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
            log.warnf("Failed to wipe workspace dir %s: %s", workspaceDir, e.getMessage());
        }
    }
}
