package com.arcclaw.persistence.sqlite;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.state.CronStatus;
import com.arcclaw.core.util.TemporalUtils;
import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.CronStorePort;
import com.arcclaw.persistence.common.entity.AgentCronEntity;
import io.quarkus.arc.Unremovable;
import org.jboss.logging.Logger;

/**
 * SQLite implementation of CronStorePort.
 * §1.2: All JPQL moved from core to here.
 * §7.33: Native queries preferred for bulk updates to bypass Hibernate 6 bulk JPQL converter bypass bug.
 */
@ApplicationScoped
@Unremovable
public class SqliteCronStoreAdapter implements CronStorePort {

    private static final Logger log = Logger.getLogger(SqliteCronStoreAdapter.class);

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
        // IMPORTANT: em.clear() is the fix for the "chat mode changes not immediately visible" bug.
        // When a cron status write and this read share the same REQUIRED persistence context
        // (e.g. ApproveReflex step 5), Hibernate's L1 cache returns the pre-write snapshot.
        // Clearing the cache forces a fresh DB read. Do not remove this.
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
        // IMPORTANT: em.clear() is the fix for the "chat mode changes not immediately visible" bug.
        // When a cron status write and this read share the same REQUIRED persistence context
        // (e.g. ApproveReflex step 5), Hibernate's L1 cache returns the pre-write snapshot.
        // Clearing the cache forces a fresh DB read. Do not remove this.
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
    public List<AgentCronDto> findAllAwaitingApproval() {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCronEntity c WHERE c.status = :status",
                AgentCronEntity.class
        ).setParameter("status", CronStatus.AWAITING_APPROVAL)
         .getResultList()
         .stream()
         .map(this::toDto)
         .collect(Collectors.toList());
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<AgentCronDto> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        // IMPORTANT: em.clear() is the fix for the "chat mode changes not immediately visible" bug.
        em.clear();
        AgentCronEntity entity = em.find(AgentCronEntity.class, id);
        return Optional.ofNullable(entity).map(this::toDto);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean claimForFire(String id) {
        if (id == null || id.isBlank()) return false;
        int updated = em.createNativeQuery(
                "UPDATE agent_cron SET status=:running WHERE id=:id AND status=:active"
        ).setParameter("id", id)
         .setParameter("running", CronStatus.RUNNING.name())
         .setParameter("active", CronStatus.ACTIVE.name())
         .executeUpdate();
        return updated == 1;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void releaseClaimToActive(String id) {
        if (id == null || id.isBlank()) return;
        em.createNativeQuery(
                "UPDATE agent_cron SET status=:active WHERE id=:id AND status=:running"
        ).setParameter("id", id)
         .setParameter("active", CronStatus.ACTIVE.name())
         .setParameter("running", CronStatus.RUNNING.name())
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markFired(String id, Instant lastFireTime, Instant nextFireTime) {
        if (id == null || id.isBlank()) {
            log.warnf("markFired called with null/blank id - skipping");
            return;
        }
        // Bulk update uses native query to avoid type mismatch on Instant fields (H6 bug)
        em.createNativeQuery(
                "UPDATE agent_cron SET status = :active, lastFireTime = :last, nextFireTime = :next, updatedAt = :now WHERE id = :id"
        ).setParameter("active", CronStatus.ACTIVE.name())
         .setParameter("last", lastFireTime != null ? TemporalUtils.formatIsoLex(lastFireTime) : null)
         .setParameter("next", nextFireTime != null ? TemporalUtils.formatIsoLex(nextFireTime) : null)
         .setParameter("now", TemporalUtils.formatIsoLex(Instant.now()))
         .setParameter("id", id)
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markCompleted(String id, Instant lastFireTime) {
        if (id == null || id.isBlank()) {
            log.warnf("markCompleted called with null/blank id - skipping");
            return;
        }
        // Bulk update uses native query to avoid type mismatch on Instant fields (H6 bug)
        em.createNativeQuery(
                "UPDATE agent_cron SET status = :completed, lastFireTime = :last, updatedAt = :now WHERE id = :id"
        ).setParameter("completed", CronStatus.COMPLETED.name())
         .setParameter("last", lastFireTime != null ? TemporalUtils.formatIsoLex(lastFireTime) : null)
         .setParameter("now", TemporalUtils.formatIsoLex(Instant.now()))
         .setParameter("id", id)
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markAwaitingApproval(String id) {
        if (id == null || id.isBlank()) {
            log.warnf("markAwaitingApproval called with null/blank id - skipping");
            return;
        }
        // Bulk update uses native query to avoid type mismatch on Instant fields (H6 bug)
        em.createNativeQuery(
                "UPDATE agent_cron SET status = :awaiting, updatedAt = :now WHERE id = :id"
        ).setParameter("awaiting", CronStatus.AWAITING_APPROVAL.name())
         .setParameter("now", TemporalUtils.formatIsoLex(Instant.now()))
         .setParameter("id", id)
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markRunning(String id) {
        if (id == null || id.isBlank()) return;
        em.createNativeQuery(
                "UPDATE agent_cron SET status = :running, updatedAt = :now WHERE id = :id"
        ).setParameter("running", CronStatus.RUNNING.name())
         .setParameter("now", TemporalUtils.formatIsoLex(Instant.now()))
         .setParameter("id", id)
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void setDeliveryId(String id, String deliveryId) {
        if (id == null || id.isBlank()) return;
        em.createNativeQuery(
                "UPDATE agent_cron SET lastDeliveryId=:d WHERE id=:id"
        ).setParameter("d", deliveryId)
         .setParameter("id", id)
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markFailed(String id, String errorReason) {
        if (id == null || id.isBlank()) {
            log.warnf("markFailed called with null/blank id - skipping");
            return;
        }
        // Bulk update uses native query to avoid type mismatch on Instant fields (H6 bug)
        em.createNativeQuery(
                "UPDATE agent_cron SET status = :failed, errorReason = :err, updatedAt = :now WHERE id = :id"
        ).setParameter("failed", CronStatus.FAILED.name())
         .setParameter("err", errorReason)
         .setParameter("now", TemporalUtils.formatIsoLex(Instant.now()))
         .setParameter("id", id)
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markSkipped(String id, Instant nextFireTime, Instant now) {
        if (id == null || id.isBlank()) {
            log.warnf("markSkipped called with null/blank id - skipping");
            return;
        }
        // Bulk update uses native query to avoid type mismatch on Instant fields (H6 bug)
        em.createNativeQuery(
            "UPDATE agent_cron SET status = :skipped, nextFireTime = :next, updatedAt = :now WHERE id = :id"
         ).setParameter("skipped", CronStatus.SKIPPED.name())
          .setParameter("next", nextFireTime != null ? TemporalUtils.formatIsoLex(nextFireTime) : null)
          .setParameter("now", now != null ? TemporalUtils.formatIsoLex(now) : null)
         .setParameter("id", id)
         .executeUpdate();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void reclaimStaleCrons(Instant threshold) {
        if (threshold == null) return;
        // Bulk update uses native query to avoid type mismatch on Instant fields (H6 bug)
        em.createNativeQuery(
                "UPDATE agent_cron SET status = :active " +
                "WHERE status = :running AND " +
                "(lastFireTime <= :threshold OR (lastFireTime IS NULL AND createdAt <= :threshold))"
        ).setParameter("active", CronStatus.ACTIVE.name())
         .setParameter("running", CronStatus.RUNNING.name())
         .setParameter("threshold", TemporalUtils.formatIsoLex(threshold))
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

        em.merge(entity);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int deleteByIdAndUserId(String id, String userId) {
        if (id == null || id.isBlank()) return 0;
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

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<AgentCronDto> findAll(String status, int offset, int limit) {
        em.clear();
        if (status != null && !status.isBlank()) {
            return em.createQuery(
                    "SELECT c FROM AgentCronEntity c WHERE c.status = :status ORDER BY c.nextFireTime ASC",
                    AgentCronEntity.class)
             .setParameter("status", CronStatus.valueOf(status))
             .setFirstResult(offset)
             .setMaxResults(limit)
             .getResultList()
             .stream().map(this::toDto).collect(Collectors.toList());
        }
        return em.createQuery(
                "SELECT c FROM AgentCronEntity c ORDER BY c.nextFireTime ASC",
                AgentCronEntity.class)
         .setFirstResult(offset)
         .setMaxResults(limit)
         .getResultList()
         .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public long countAll(String status) {
        em.clear();
        if (status != null && !status.isBlank()) {
            return em.createQuery(
                    "SELECT COUNT(c) FROM AgentCronEntity c WHERE c.status = :status", Long.class)
             .setParameter("status", CronStatus.valueOf(status))
             .getSingleResult();
        }
        return em.createQuery("SELECT COUNT(c) FROM AgentCronEntity c", Long.class)
         .getSingleResult();
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

