package com.arcclaw.persistence.postgres;

import java.time.Instant;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.common.entity.AgentCheckpointEntity;
import io.quarkus.arc.properties.IfBuildProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class ZombieRecoveryService {

    private static final Logger log = Logger.getLogger(ZombieRecoveryService.class);

    @Inject EntityManager em;

    public List<CheckpointDto> findStaleApprovals(Instant cutoff) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.status = :status AND c.updatedAt < :cutoff", AgentCheckpointEntity.class)
                .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("cutoff", cutoff)
                .getResultStream()
                .map(CheckpointMapper::toDto)
                .toList();
    }

    public boolean isSessionActive(String sessionId) {
        Long count = em.createQuery("SELECT COUNT(c) FROM AgentCheckpoint c WHERE c.sessionId = :sessionId AND c.status NOT IN (:terminal)", Long.class)
                .setParameter("sessionId", sessionId)
                .setParameter("terminal", List.of(CheckpointStatus.COMPLETED, CheckpointStatus.FAILED, CheckpointStatus.DENIED))
                .getSingleResult();
        return count > 0;
    }

    public List<CheckpointDto> findInProgressZombies(Instant cutoff) {
        return em.createQuery("SELECT c FROM AgentCheckpoint c WHERE c.status = :status AND c.updatedAt < :cutoff", AgentCheckpointEntity.class)
                .setParameter("status", CheckpointStatus.IN_PROGRESS)
                .setParameter("cutoff", cutoff)
                .getResultStream()
                .map(CheckpointMapper::toDto)
                .toList();
    }

    @Transactional
    public List<CheckpointDto> recoverInProgressZombies(Instant inProgressCutoff, String failReason) {
        List<CheckpointDto> zombies = findInProgressZombies(inProgressCutoff);
        for (CheckpointDto z : zombies) {
            markFailed(z.id(), failReason, "ZOMBIE_RECOVERY", null, null);
        }
        return zombies;
    }

    @Transactional
    public void markCheckpointsFailed(String sessionId) {
        int affected = em.createQuery("UPDATE AgentCheckpoint c SET c.status = :failed WHERE c.sessionId = :sessionId AND c.status NOT IN (:terminal)")
                .setParameter("failed", CheckpointStatus.FAILED)
                .setParameter("sessionId", sessionId)
                .setParameter("terminal", List.of(CheckpointStatus.COMPLETED, CheckpointStatus.FAILED, CheckpointStatus.DENIED, CheckpointStatus.ARCHIVED))
                .executeUpdate();
        if (affected > 0) {
            log.warnf("[ZombieRecovery] Marked %d active checkpoints as FAILED for session=%s", affected, sessionId);
        } else {
            log.debugf("[ZombieRecovery] No active checkpoints to fail for session=%s", sessionId);
        }
    }

    @Transactional
    public void markFailed(Long checkpointId, String failReason, String errorCode, String traceId, String rootCauseClass) {
        em.createQuery("UPDATE AgentCheckpoint c SET c.status = :failed, c.failReason = :reason, c.errorCode = :code, c.traceId = :trace, c.rootCauseClass = :root, c.failedAt = :now WHERE c.id = :id")
                .setParameter("failed", CheckpointStatus.FAILED)
                .setParameter("reason", failReason)
                .setParameter("code", errorCode)
                .setParameter("trace", traceId)
                .setParameter("root", rootCauseClass)
                .setParameter("now", Instant.now())
                .setParameter("id", checkpointId)
                .executeUpdate();
    }

    @Transactional
    public void purgeOldChatHistory(Instant cutoff) {
        em.createQuery("DELETE FROM ReasoningStepEntity r WHERE r.checkpointId IN " +
                       "(SELECT c.id FROM AgentCheckpoint c WHERE c.updatedAt < :cutoff)")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
        em.createQuery("DELETE FROM AgentCheckpoint c WHERE c.updatedAt < :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
    }
}

