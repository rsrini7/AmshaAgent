package com.arcclaw.persistence.sqlite;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.CronStatus;
import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.CronStorePort;
import io.quarkus.arc.Unremovable;

/**
 * SQLite implementation of CronStorePort.
 * §1.2: All JPQL moved from core to here.
 */
@ApplicationScoped
@Unremovable
public class SqliteCronStoreAdapter implements CronStorePort {

    @Inject
    EntityManager em;

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<AgentCronDto> findDueCrons() {
        return em.createQuery(
                "SELECT c FROM AgentCronEntity c WHERE c.status = :status AND c.nextFireTime <= :now",
                AgentCronEntity.class
        ).setParameter("status", CronStatus.ACTIVE)
         .setParameter("now", Instant.now())
         .getResultList()
         .stream()
         .map(this::toDto)
         .collect(Collectors.toList());
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<AgentCronDto> findByUserId(String userId) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCronEntity c WHERE c.userId = :userId",
                AgentCronEntity.class
        ).setParameter("userId", userId)
         .getResultList()
         .stream()
         .map(this::toDto)
         .collect(Collectors.toList());
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<AgentCronDto> findAwaitingApprovalByUserId(String userId) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCronEntity c WHERE c.userId = :userId AND c.status = :status",
                AgentCronEntity.class
        ).setParameter("userId", userId)
         .setParameter("status", CronStatus.AWAITING_APPROVAL)
         .getResultList()
         .stream()
         .map(this::toDto)
         .collect(Collectors.toList());
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<AgentCronDto> findById(String id) {
        AgentCronEntity entity = em.find(AgentCronEntity.class, id);
        return Optional.ofNullable(entity).map(this::toDto);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean claimForFire(String id) {
        int updated = em.createNativeQuery(
                "UPDATE agentcron SET status=:running WHERE id=:id AND status=:active"
        ).setParameter("id", id)
         .setParameter("running", CronStatus.RUNNING.name())
         .setParameter("active", CronStatus.ACTIVE.name())
         .executeUpdate();
        return updated == 1;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void releaseClaimToActive(String id) {
        em.createNativeQuery(
                "UPDATE agentcron SET status=:active WHERE id=:id AND status=:running"
        ).setParameter("id", id)
         .setParameter("active", CronStatus.ACTIVE.name())
         .setParameter("running", CronStatus.RUNNING.name())
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markFired(String id, Instant lastFireTime, Instant nextFireTime) {
        em.createQuery(
                "UPDATE AgentCronEntity c SET c.status = :active, c.lastFireTime = :last, c.nextFireTime = :next, c.updatedAt = :now WHERE c.id = :id"
        ).setParameter("active", CronStatus.ACTIVE)
         .setParameter("last", lastFireTime)
         .setParameter("next", nextFireTime)
         .setParameter("now", Instant.now())
         .setParameter("id", id)
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markCompleted(String id, Instant lastFireTime) {
        em.createQuery(
                "UPDATE AgentCronEntity c SET c.status = :completed, c.lastFireTime = :last, c.updatedAt = :now WHERE c.id = :id"
        ).setParameter("completed", CronStatus.COMPLETED)
         .setParameter("last", lastFireTime)
         .setParameter("now", Instant.now())
         .setParameter("id", id)
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markAwaitingApproval(String id) {
        em.createQuery(
                "UPDATE AgentCronEntity c SET c.status = :awaiting, c.updatedAt = :now WHERE c.id = :id"
        ).setParameter("awaiting", CronStatus.AWAITING_APPROVAL)
         .setParameter("now", Instant.now())
         .setParameter("id", id)
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void setDeliveryId(String id, String deliveryId) {
        em.createNativeQuery(
                "UPDATE agentcron SET lastDeliveryId=:d WHERE id=:id"
        ).setParameter("d", deliveryId)
         .setParameter("id", id)
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markFailed(String id, String errorReason) {
        em.createQuery(
                "UPDATE AgentCronEntity c SET c.status = :failed, c.errorReason = :err, c.updatedAt = :now WHERE c.id = :id"
        ).setParameter("failed", CronStatus.FAILED)
         .setParameter("err", errorReason)
         .setParameter("now", Instant.now())
         .setParameter("id", id)
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markSkipped(String id, Instant nextFireTime, Instant now) {
        em.createQuery(
            "UPDATE AgentCronEntity c SET c.status = :skipped, c.nextFireTime = :next, c.updatedAt = :now WHERE c.id = :id"
         ).setParameter("skipped", CronStatus.SKIPPED)
          .setParameter("next", nextFireTime)
          .setParameter("now", now)
         .setParameter("id", id)
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void reclaimStaleCrons(Instant threshold) {
        em.createQuery(
                "UPDATE AgentCronEntity c SET c.status = :active " +
                "WHERE c.status = :running AND " +
                "(c.lastFireTime <= :threshold OR (c.lastFireTime IS NULL AND c.createdAt <= :threshold))"
        ).setParameter("active", CronStatus.ACTIVE)
         .setParameter("running", CronStatus.RUNNING)
         .setParameter("threshold", threshold)
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void save(AgentCronDto dto) {
        AgentCronEntity entity = em.find(AgentCronEntity.class, dto.id());
        if (entity == null) {
            entity = new AgentCronEntity();
            entity.id = dto.id();
            entity.createdAt = Instant.now();
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
        entity.nextFireTime = dto.nextFireTime();
        entity.lastFireTime = dto.lastFireTime();
        entity.status = dto.status() != null ? CronStatus.valueOf(dto.status()) : null;
        entity.executionRole = dto.executionRole();
        entity.errorReason = dto.errorReason();
        entity.lastDeliveryId = dto.lastDeliveryId();
        entity.updatedAt = Instant.now();

        if (em.contains(entity)) {
            em.merge(entity);
        } else {
            em.persist(entity);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int deleteByIdAndUserId(String id, String userId) {
        return em.createQuery(
                "DELETE FROM AgentCronEntity c WHERE c.id = :id AND c.userId = :userId"
        ).setParameter("id", id)
         .setParameter("userId", userId)
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public long deleteAllByUserId(String userId) {
        return em.createQuery(
                "DELETE FROM AgentCronEntity c WHERE c.userId = :userId")
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public long deleteAllBySessionId(String sessionId) {
        return em.createQuery(
                "DELETE FROM AgentCronEntity c WHERE c.sessionId = :sessionId")
                 .setParameter("sessionId", sessionId)
                 .executeUpdate();
    }

    private AgentCronDto toDto(AgentCronEntity entity) {
        if (entity == null) return null;
        return new AgentCronDto(
                entity.id,
                entity.sessionId,
                entity.userId,
                entity.chatId,
                entity.channelType,
                entity.channelPlatformId,
                entity.cronExpression,
                entity.timezone,
                entity.runOnce,
                entity.goal,
                entity.nextFireTime,
                entity.lastFireTime,
                entity.status != null ? entity.status.name() : null,
                entity.executionRole,
                entity.errorReason,
                entity.lastDeliveryId,
                entity.createdAt,
                entity.updatedAt
        );
    }
}
