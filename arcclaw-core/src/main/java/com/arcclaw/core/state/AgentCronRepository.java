package com.arcclaw.core.state;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.CronStorePort;
import io.quarkus.arc.Unremovable;

/**
 * Repository for {@link AgentCronEntity} refactored to delegate to {@link CronStorePort}.
 * §1.2: Zero EntityManager imports, zero JPQL.
 */
@ApplicationScoped
@Unremovable
public class AgentCronRepository {

    @Inject
    CronStorePort cronStore;

    @Transactional(TxType.NOT_SUPPORTED)
    public List<AgentCronEntity> findDueCrons() {
        return cronStore.findDueCrons().stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Transactional(TxType.REQUIRED)
    public List<AgentCronEntity> findByUserId(String userId) {
        return cronStore.findByUserId(userId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Transactional(TxType.REQUIRED)
    public List<AgentCronEntity> findAwaitingApprovalByUserId(String userId) {
        return cronStore.findAwaitingApprovalByUserId(userId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Transactional(TxType.NOT_SUPPORTED)
    public AgentCronEntity findById(String id) {
        return cronStore.findById(id).map(this::toEntity).orElse(null);
    }

    @Transactional(TxType.REQUIRED)
    public boolean claimForFire(String id) {
        return cronStore.claimForFire(id);
    }

    @Transactional(TxType.REQUIRED)
    public void releaseClaimToActive(String id) {
        cronStore.releaseClaimToActive(id);
    }

    @Transactional(TxType.REQUIRED)
    public void markFired(String id, Instant lastFireTime, Instant nextFireTime) {
        cronStore.markFired(id, lastFireTime, nextFireTime);
    }

    @Transactional(TxType.REQUIRED)
    public void markCompleted(String id, Instant lastFireTime) {
        cronStore.markCompleted(id, lastFireTime);
    }

    @Transactional(TxType.REQUIRED)
    public void markAwaitingApproval(String id) {
        cronStore.markAwaitingApproval(id);
    }

    @Transactional(TxType.REQUIRED)
    public void setDeliveryId(String id, String deliveryId) {
        cronStore.setDeliveryId(id, deliveryId);
    }

    @Transactional(TxType.REQUIRED)
    public void markFailed(String id, String errorReason) {
        cronStore.markFailed(id, errorReason);
    }

    @Transactional(TxType.REQUIRED)
    public void markSkipped(List<AgentCronEntity> skippedJobs) {
        if (skippedJobs.isEmpty()) return;
        Instant now = Instant.now();
        for (AgentCronEntity job : skippedJobs) {
            cronStore.markSkipped(job.id, job.nextFireTime, now);
        }
    }

    @Transactional(TxType.REQUIRED)
    public void reclaimStaleCrons(Instant threshold) {
        cronStore.reclaimStaleCrons(threshold);
    }

    @Transactional(TxType.REQUIRED)
    public void persist(AgentCronEntity entity) {
        cronStore.save(toDto(entity));
    }

    @Transactional(TxType.REQUIRED)
    public int deleteByIdAndUserId(String id, String userId) {
        return cronStore.deleteByIdAndUserId(id, userId);
    }

    @Transactional(TxType.REQUIRES_NEW)
    public long deleteAllByUserId(String userId) {
        return cronStore.deleteAllByUserId(userId);
    }

    @Transactional(TxType.REQUIRES_NEW)
    public long deleteAllBySessionId(String sessionId) {
        return cronStore.deleteAllBySessionId(sessionId);
    }

    private AgentCronEntity toEntity(AgentCronDto dto) {
        if (dto == null) return null;
        AgentCronEntity entity = new AgentCronEntity();
        entity.id = dto.id();
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
        entity.createdAt = dto.createdAt();
        entity.updatedAt = dto.updatedAt();
        return entity;
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
