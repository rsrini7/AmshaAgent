package com.arcclaw.persistence.sqlite;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.persistence.CheckpointMapper;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.common.entity.AgentCheckpointEntity;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.scheduler.Scheduled;
import org.jboss.logging.Logger;

/**
 * Handles background cleanup of stale checkpoints, orphaned sessions, and old chat history.
 */
@ApplicationScoped
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class CheckpointCleanupService {

    private static final Logger log = Logger.getLogger(CheckpointCleanupService.class);

    private final EntityManager em;
    private final SqliteCheckpointStoreAdapter adapter;

    @Inject
    public CheckpointCleanupService(EntityManager em,
                                   SqliteCheckpointStoreAdapter adapter) {
        this.em = em;
        this.adapter = adapter;
    }

    protected CheckpointCleanupService() {
        this(null, null);
    }

    @Scheduled(every = "12h", delayed = "1h")
    public void scheduledPurge() {
        log.info("[CLEANUP-SERVICE] Starting scheduled purge of old chat history...");
        Instant cutoff = Instant.now().minus(Duration.ofDays(7));
        adapter.purgeOldChatHistory(cutoff);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<CheckpointDto> doRecoverInProgressZombies(Instant inProgressCutoff, String failReason) {
        List<CheckpointDto> zombies = findInProgressZombies(inProgressCutoff);
        if (zombies.isEmpty()) return zombies;

        em.createQuery("UPDATE AgentCheckpoint c SET c.status = :failed, c.failReason = :reason, " +
                       "c.errorCode = :code, c.failedAt = :now, c.updatedAt = :now " +
                       "WHERE c.status = :status AND c.updatedAt < :cutoff")
                .setParameter("failed", CheckpointStatus.FAILED)
                .setParameter("reason", failReason)
                .setParameter("code", "ZOMBIE")
                .setParameter("now", Instant.now())
                .setParameter("status", CheckpointStatus.IN_PROGRESS)
                .setParameter("cutoff", inProgressCutoff)
                .executeUpdate();

        return zombies;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doMarkCheckpointsFailed(String sessionId) {
        int affected = em.createQuery("UPDATE AgentCheckpoint c SET c.status = :failed, c.failedAt = :now, c.updatedAt = :now " +
                       "WHERE c.sessionId = :sid AND c.status NOT IN (:terminalStatuses)")
                .setParameter("failed", CheckpointStatus.FAILED)
                .setParameter("now", Instant.now())
                .setParameter("sid", sessionId)
                .setParameter("terminalStatuses", CheckpointStatus.terminalStatuses())
                .executeUpdate();
        if (affected > 0) {
            log.warnf("[CheckpointCleanup] Marked %d active checkpoints as FAILED for session=%s", affected, sessionId);
        } else {
            log.debugf("[CheckpointCleanup] No active checkpoints to fail for session=%s", sessionId);
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doDeleteCheckpoint(String sessionId, String role) {
        List<AgentCheckpointEntity> results = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.agentRole = :role",
                AgentCheckpointEntity.class)
                .setParameter("sid", sessionId)
                .setParameter("role", role)
                .getResultList();
        results.forEach(em::remove);
    }

    public List<CheckpointDto> findInProgressZombies(Instant cutoff) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status AND c.updatedAt < :cutoff",
                AgentCheckpointEntity.class)
                .setParameter("status", CheckpointStatus.IN_PROGRESS)
                .setParameter("cutoff", cutoff)
                .getResultList()
                .stream()
                .map(CheckpointMapper::toDto)
                .collect(Collectors.toList());
    }

    public List<CheckpointDto> findStaleApprovals(Instant cutoff) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status AND c.updatedAt < :cutoff",
                AgentCheckpointEntity.class)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("cutoff", cutoff)
                .getResultList()
                .stream()
                .map(CheckpointMapper::toDto)
                .collect(Collectors.toList());
    }

    public boolean isCheckpointExpired(String sessionId, long timeoutHours) {
        Instant cutoff = Instant.now().minus(timeoutHours, ChronoUnit.HOURS);
        List<AgentCheckpointEntity> results = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.status = :status AND c.updatedAt < :cutoff",
                AgentCheckpointEntity.class)
                .setParameter("sid", sessionId)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("cutoff", cutoff)
                .getResultList();
        return !results.isEmpty();
    }

    public boolean isSessionActive(String sessionId) {
        List<AgentCheckpointEntity> results = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.status NOT IN (:terminalStatuses)",
                AgentCheckpointEntity.class)
                .setParameter("sid", sessionId)
                .setParameter("terminalStatuses", CheckpointStatus.terminalStatuses())
                .setMaxResults(1)
                .getResultList();
        return !results.isEmpty();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doPurgeOldChatHistory(Instant cutoff) {
        em.createQuery("DELETE FROM ReasoningStepEntity r WHERE r.checkpointId IN " +
                       "(SELECT c.id FROM AgentCheckpoint c WHERE c.updatedAt < :cutoff)")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
        em.createQuery("DELETE FROM AgentCheckpoint c WHERE c.updatedAt < :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
    }
}

