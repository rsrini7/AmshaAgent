package com.arcclaw.core.state;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.domain.model.AgentCron;
import com.arcclaw.core.util.CronScheduleUtil;
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
    public List<AgentCron> findDueCrons() {
        return cronStore.findDueCrons().stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Transactional(TxType.REQUIRED)
    public List<AgentCron> findByUserId(String userId) {
        return cronStore.findByUserId(userId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Transactional(TxType.REQUIRED)
    public List<AgentCron> findAwaitingApprovalByUserId(String userId) {
        return cronStore.findAwaitingApprovalByUserId(userId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Transactional(TxType.REQUIRED)
    public List<AgentCron> findAllAwaitingApproval() {
        return cronStore.findAllAwaitingApproval().stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Transactional(TxType.NOT_SUPPORTED)
    public AgentCron findById(String id) {
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
    public void markRunning(String id) {
        cronStore.markRunning(id);
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
    public void markSkipped(List<AgentCron> skippedJobs) {
        if (skippedJobs.isEmpty()) return;
        Instant now = Instant.now();
        for (AgentCron job : skippedJobs) {
            cronStore.markSkipped(job.getId(), job.getNextFireTime(), now);
        }
    }

    @Transactional(TxType.REQUIRED)
    public void reclaimStaleCrons(Instant threshold) {
        cronStore.reclaimStaleCrons(threshold);
    }

    @Transactional(TxType.REQUIRED)
    public void persist(AgentCron entity) {
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

    /**
     * Unified terminal status synchronization for cron tasks.
     * Transitions the task from RUNNING to its final state based on the agent result.
     */
    @Transactional(TxType.REQUIRED)
    public void markTerminal(String id, AgentResult result) {
        AgentCron cron = findById(id);
        if (cron == null || result == null) return;

        switch (result) {
            case AgentResult.Finished _ -> {
                if (cron.isRunOnce()) markCompleted(cron.getId(), Instant.now());
                else markFired(cron.getId(), Instant.now(), CronScheduleUtil.computeNext(cron.getCronExpression(), cron.getTimezone()));
            }
            case AgentResult.StructuredFinished _ -> {
                if (cron.isRunOnce()) markCompleted(cron.getId(), Instant.now());
                else markFired(cron.getId(), Instant.now(), CronScheduleUtil.computeNext(cron.getCronExpression(), cron.getTimezone()));
            }
            case AgentResult.Failed f -> markFailed(cron.getId(), f.reason());
            case AgentResult.AwaitingApproval _ -> markAwaitingApproval(cron.getId());
            case AgentResult.AwaitingRetry _ -> {} // No-op - retry handled by RateLimitRetryScheduler
            case AgentResult.LoopExceeded _ -> markFailed(cron.getId(), "Loop exceeded");
            default -> {}
        }
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

    private AgentCronDto toDto(AgentCron entity) {
        if (entity == null) return null;
        return new AgentCronDto(
                entity.getId(),
                entity.getSessionId(),
                entity.getUserId(),
                entity.getChatId(),
                entity.getChannelType(),
                entity.getChannelPlatformId(),
                entity.getCronExpression(),
                entity.getTimezone(),
                entity.isRunOnce(),
                entity.getGoal(),
                entity.getNextFireTime(),
                entity.getLastFireTime(),
                entity.getStatus() != null ? entity.getStatus().name() : null,
                entity.getExecutionRole(),
                entity.getErrorReason(),
                entity.getLastDeliveryId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
