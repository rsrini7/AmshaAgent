package com.arcclaw.persistence.postgres;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.CronStatus;
import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.CronStorePort;
import org.jboss.logging.Logger;

/**
 * PostgreSQL implementation of CronStorePort.
 */
@ApplicationScoped
public class PostgresCronStoreAdapter implements CronStorePort {

    private static final Logger log = Logger.getLogger(PostgresCronStoreAdapter.class);

    @Inject EntityManager em;

    @Override
    public List<AgentCronDto> findDueCrons() {
        return em.createQuery("SELECT e FROM AgentCronEntity e WHERE e.status = :status AND e.nextFireTime <= :now", AgentCronEntity.class)
                .setParameter("status", CronStatus.ACTIVE)
                .setParameter("now", Instant.now())
                .getResultStream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public List<AgentCronDto> findByUserId(String userId) {
        return em.createQuery("SELECT e FROM AgentCronEntity e WHERE e.userId = :userId", AgentCronEntity.class)
                .setParameter("userId", userId)
                .getResultStream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public List<AgentCronDto> findAwaitingApprovalByUserId(String userId) {
        return em.createQuery("SELECT e FROM AgentCronEntity e WHERE e.userId = :userId AND e.status = :status", AgentCronEntity.class)
                .setParameter("userId", userId)
                .setParameter("status", CronStatus.AWAITING_APPROVAL)
                .getResultStream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public List<AgentCronDto> findAllAwaitingApproval() {
        return em.createQuery("SELECT e FROM AgentCronEntity e WHERE e.status = :status", AgentCronEntity.class)
                .setParameter("status", CronStatus.AWAITING_APPROVAL)
                .getResultStream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public Optional<AgentCronDto> findById(String id) {
        return Optional.ofNullable(em.find(AgentCronEntity.class, id)).map(this::toDto);
    }

    @Override
    @Transactional
    public boolean claimForFire(String id) {
        int updated = em.createQuery("UPDATE AgentCronEntity e SET e.status = :running WHERE e.id = :id AND e.status = :active")
                .setParameter("running", CronStatus.RUNNING)
                .setParameter("id", id)
                .setParameter("active", CronStatus.ACTIVE)
                .executeUpdate();
        return updated > 0;
    }

    @Override
    @Transactional
    public void releaseClaimToActive(String id) {
        em.createQuery("UPDATE AgentCronEntity e SET e.status = :active WHERE e.id = :id AND e.status = :running")
                .setParameter("active", CronStatus.ACTIVE)
                .setParameter("id", id)
                .setParameter("running", CronStatus.RUNNING)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void markFired(String id, Instant lastFireTime, Instant nextFireTime) {
        em.createQuery("UPDATE AgentCronEntity e SET e.status = :active, e.lastFireTime = :last, e.nextFireTime = :next WHERE e.id = :id")
                .setParameter("active", CronStatus.ACTIVE)
                .setParameter("last", lastFireTime)
                .setParameter("next", nextFireTime)
                .setParameter("id", id)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void markCompleted(String id, Instant lastFireTime) {
        em.createQuery("UPDATE AgentCronEntity e SET e.status = :completed, e.lastFireTime = :last, e.nextFireTime = null WHERE e.id = :id")
                .setParameter("completed", CronStatus.COMPLETED)
                .setParameter("last", lastFireTime)
                .setParameter("id", id)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void markAwaitingApproval(String id) {
        em.createQuery("UPDATE AgentCronEntity e SET e.status = :status WHERE e.id = :id")
                .setParameter("status", CronStatus.AWAITING_APPROVAL)
                .setParameter("id", id)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void markRunning(String id) {
        em.createQuery("UPDATE AgentCronEntity e SET e.status = :status WHERE e.id = :id")
                .setParameter("status", CronStatus.RUNNING)
                .setParameter("id", id)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void setDeliveryId(String id, String deliveryId) {
        em.createQuery("UPDATE AgentCronEntity e SET e.lastDeliveryId = :deliveryId WHERE e.id = :id")
                .setParameter("deliveryId", deliveryId)
                .setParameter("id", id)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void markFailed(String id, String errorReason) {
        em.createQuery("UPDATE AgentCronEntity e SET e.status = :failed, e.errorReason = :reason WHERE e.id = :id")
                .setParameter("failed", CronStatus.FAILED)
                .setParameter("reason", errorReason)
                .setParameter("id", id)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void markSkipped(String id, Instant nextFireTime, Instant now) {
        em.createQuery("UPDATE AgentCronEntity e SET e.nextFireTime = :next, e.lastFireTime = :now WHERE e.id = :id")
                .setParameter("next", nextFireTime)
                .setParameter("now", now)
                .setParameter("id", id)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void reclaimStaleCrons(Instant threshold) {
        em.createQuery("UPDATE AgentCronEntity e SET e.status = :active WHERE e.status = :running AND e.updatedAt < :threshold")
                .setParameter("active", CronStatus.ACTIVE)
                .setParameter("running", CronStatus.RUNNING)
                .setParameter("threshold", threshold)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void save(AgentCronDto dto) {
        AgentCronEntity entity = em.find(AgentCronEntity.class, dto.id());
        if (entity == null) {
            entity = new AgentCronEntity();
            entity.id = dto.id();
        }
        entity.sessionId = dto.sessionId();
        entity.userId = dto.userId();
        entity.chatId = dto.chatId();
        entity.channelType = dto.channelType();
        entity.channelPlatformId = dto.channelPlatformId();
        entity.cronExpression = dto.cronExpression();
        entity.timezone = dto.timezone();
        entity.runOnce = dto.runOnce();
        entity.goal = dto.goal();
        entity.status = dto.status() != null ? CronStatus.valueOf(dto.status()) : null;
        entity.nextFireTime = dto.nextFireTime();
        entity.lastFireTime = dto.lastFireTime();
        entity.lastDeliveryId = dto.lastDeliveryId();
        entity.errorReason = dto.errorReason();
        entity.createdAt = dto.createdAt();
        entity.updatedAt = Instant.now();
        
        em.merge(entity);
    }

    @Override
    @Transactional
    public int deleteByIdAndUserId(String id, String userId) {
        return em.createQuery("DELETE FROM AgentCronEntity e WHERE e.id = :id AND e.userId = :userId")
                .setParameter("id", id)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public long deleteAllByUserId(String userId) {
        return em.createQuery("DELETE FROM AgentCronEntity e WHERE e.userId = :userId")
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public long deleteAllBySessionId(String sessionId) {
        return em.createQuery("DELETE FROM AgentCronEntity e WHERE e.sessionId = :sessionId")
                .setParameter("sessionId", sessionId)
                .executeUpdate();
    }

    private AgentCronDto toDto(AgentCronEntity e) {
        return new AgentCronDto(
                e.id, e.sessionId, e.userId, e.chatId, e.channelType,
                e.channelPlatformId, e.cronExpression, e.timezone, e.runOnce, e.goal,
                e.nextFireTime, e.lastFireTime, e.status != null ? e.status.name() : null,
                e.executionRole, e.errorReason, e.lastDeliveryId, e.createdAt, e.updatedAt
        );
    }
}
