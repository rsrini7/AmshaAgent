package com.arcclaw.persistence.fs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.state.CronStatus;
import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.CronStorePort;
import io.quarkus.arc.Unremovable;
import org.jboss.logging.Logger;

@ApplicationScoped
@Unremovable
public class FsCronStoreAdapter implements CronStorePort {

    private static final Logger log = Logger.getLogger(FsCronStoreAdapter.class);
    private static final String DOMAIN = "crons";
    private static final String BY_ID = "by-id";
    private static final String INDICES = "indices";
    private static final String BY_USER = "by-user";

    @Inject
    FilesystemFileStore store;

    @Override
    public List<AgentCronDto> findDueCrons() {
        Instant now = Instant.now();
        return loadAll().stream()
            .filter(dto -> CronStatus.ACTIVE.name().equals(dto.status()))
            .filter(dto -> dto.nextFireTime() != null && !dto.nextFireTime().isAfter(now))
            .toList();
    }

    @Override
    public List<AgentCronDto> findByUserId(String userId) {
        return loadAll().stream()
            .filter(dto -> userId.equals(dto.userId()))
            .toList();
    }

    @Override
    public List<AgentCronDto> findAwaitingApprovalByUserId(String userId) {
        return loadAll().stream()
            .filter(dto -> userId.equals(dto.userId()))
            .filter(dto -> CronStatus.AWAITING_APPROVAL.name().equals(dto.status()))
            .toList();
    }

    @Override
    public List<AgentCronDto> findAllAwaitingApproval() {
        return loadAll().stream()
            .filter(dto -> CronStatus.AWAITING_APPROVAL.name().equals(dto.status()))
            .toList();
    }

    @Override
    public Optional<AgentCronDto> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return store.readOptional(List.of(DOMAIN, BY_ID, id + ".json"), AgentCronDto.class);
    }

    @Override
    public boolean claimForFire(String id) {
        return atomicStatusUpdate(id, CronStatus.ACTIVE.name(), CronStatus.RUNNING.name());
    }

    @Override
    public void releaseClaimToActive(String id) {
        atomicStatusUpdate(id, CronStatus.RUNNING.name(), CronStatus.ACTIVE.name());
    }

    @Override
    public void markFired(String id, Instant lastFireTime, Instant nextFireTime) {
        updateWithLock(id, dto -> new AgentCronDto(
            dto.id(), dto.sessionId(), dto.userId(), dto.chatId(),
            dto.channelType(), dto.channelPlatformId(), dto.cronExpression(),
            dto.timezone(), dto.runOnce(), dto.goal(), nextFireTime, lastFireTime,
            CronStatus.ACTIVE.name(), dto.executionRole(), dto.errorReason(), dto.lastDeliveryId(),
            dto.createdAt(), Instant.now()
        ));
    }

    @Override
    public void markCompleted(String id, Instant lastFireTime) {
        updateWithLock(id, dto -> new AgentCronDto(
            dto.id(), dto.sessionId(), dto.userId(), dto.chatId(),
            dto.channelType(), dto.channelPlatformId(), dto.cronExpression(),
            dto.timezone(), dto.runOnce(), dto.goal(), null, lastFireTime,
            CronStatus.COMPLETED.name(), dto.executionRole(), dto.errorReason(), dto.lastDeliveryId(),
            dto.createdAt(), Instant.now()
        ));
    }

    @Override
    public void markAwaitingApproval(String id) {
        updateWithLock(id, dto -> new AgentCronDto(
            dto.id(), dto.sessionId(), dto.userId(), dto.chatId(),
            dto.channelType(), dto.channelPlatformId(), dto.cronExpression(),
            dto.timezone(), dto.runOnce(), dto.goal(), dto.nextFireTime(), dto.lastFireTime(),
            CronStatus.AWAITING_APPROVAL.name(), dto.executionRole(), dto.errorReason(), dto.lastDeliveryId(),
            dto.createdAt(), Instant.now()
        ));
    }

    @Override
    public void markRunning(String id) {
        updateWithLock(id, dto -> new AgentCronDto(
            dto.id(), dto.sessionId(), dto.userId(), dto.chatId(),
            dto.channelType(), dto.channelPlatformId(), dto.cronExpression(),
            dto.timezone(), dto.runOnce(), dto.goal(), dto.nextFireTime(), dto.lastFireTime(),
            CronStatus.RUNNING.name(), dto.executionRole(), dto.errorReason(), dto.lastDeliveryId(),
            dto.createdAt(), Instant.now()
        ));
    }

    @Override
    public void setDeliveryId(String id, String deliveryId) {
        updateWithLock(id, dto -> new AgentCronDto(
            dto.id(), dto.sessionId(), dto.userId(), dto.chatId(),
            dto.channelType(), dto.channelPlatformId(), dto.cronExpression(),
            dto.timezone(), dto.runOnce(), dto.goal(), dto.nextFireTime(), dto.lastFireTime(),
            dto.status(), dto.executionRole(), dto.errorReason(), deliveryId,
            dto.createdAt(), Instant.now()
        ));
    }

    @Override
    public void markFailed(String id, String errorReason) {
        updateWithLock(id, dto -> new AgentCronDto(
            dto.id(), dto.sessionId(), dto.userId(), dto.chatId(),
            dto.channelType(), dto.channelPlatformId(), dto.cronExpression(),
            dto.timezone(), dto.runOnce(), dto.goal(), dto.nextFireTime(), dto.lastFireTime(),
            CronStatus.FAILED.name(), dto.executionRole(), errorReason, dto.lastDeliveryId(),
            dto.createdAt(), Instant.now()
        ));
    }

    @Override
    public void markSkipped(String id, Instant nextFireTime, Instant now) {
        updateWithLock(id, dto -> new AgentCronDto(
            dto.id(), dto.sessionId(), dto.userId(), dto.chatId(),
            dto.channelType(), dto.channelPlatformId(), dto.cronExpression(),
            dto.timezone(), dto.runOnce(), dto.goal(), nextFireTime, dto.lastFireTime(),
            CronStatus.SKIPPED.name(), dto.executionRole(), dto.errorReason(), dto.lastDeliveryId(),
            dto.createdAt(), now != null ? now : Instant.now()
        ));
    }

    @Override
    public void reclaimStaleCrons(Instant threshold) {
        for (AgentCronDto dto : loadAll()) {
            if (CronStatus.RUNNING.name().equals(dto.status())) {
                Instant ref = dto.lastFireTime() != null ? dto.lastFireTime() : dto.createdAt();
                if (ref != null && ref.isBefore(threshold)) {
                    atomicStatusUpdate(dto.id(), CronStatus.RUNNING.name(), CronStatus.ACTIVE.name());
                }
            }
        }
    }

    @Override
    public void save(AgentCronDto dto) {
        store.write(List.of(DOMAIN, BY_ID, dto.id() + ".json"), dto);
        if (dto.userId() != null) {
            addToUserIndex(dto.userId(), dto.id());
        }
    }

    @Override
    public int deleteByIdAndUserId(String id, String userId) {
        Optional<AgentCronDto> existing = findById(id);
        if (existing.isPresent() && existing.get().userId().equals(userId)) {
            store.delete(List.of(DOMAIN, BY_ID, id + ".json"));
            removeFromUserIndex(userId, id);
            return 1;
        }
        return 0;
    }

    @Override
    public long deleteAllByUserId(String userId) {
        List<String> ids = new ArrayList<>(loadUserIndex(userId).ids);
        for (String id : ids) {
            store.delete(List.of(DOMAIN, BY_ID, id + ".json"));
        }
        store.delete(List.of(DOMAIN, INDICES, BY_USER, userId + ".json"));
        return ids.size();
    }

    @Override
    public long deleteAllBySessionId(String sessionId) {
        long count = 0;
        for (AgentCronDto dto : loadAll()) {
            if (sessionId.equals(dto.sessionId())) {
                store.delete(List.of(DOMAIN, BY_ID, dto.id() + ".json"));
                removeFromUserIndex(dto.userId(), dto.id());
                count++;
            }
        }
        return count;
    }

    @Override
    public List<AgentCronDto> findAll(String status, int offset, int limit) {
        List<AgentCronDto> all = loadAll();
        if (status != null && !status.isBlank()) {
            all = all.stream()
                .filter(dto -> status.equals(dto.status()))
                .toList();
        }
        all = all.stream()
            .sorted((a, b) -> {
                if (a.nextFireTime() == null && b.nextFireTime() == null) return 0;
                if (a.nextFireTime() == null) return 1;
                if (b.nextFireTime() == null) return -1;
                return a.nextFireTime().compareTo(b.nextFireTime());
            })
            .toList();
        int end = Math.min(offset + limit, all.size());
        if (offset >= all.size()) return List.of();
        return all.subList(offset, end);
    }

    @Override
    public long countAll(String status) {
        if (status != null && !status.isBlank()) {
            return loadAll().stream()
                .filter(dto -> status.equals(dto.status()))
                .count();
        }
        return loadAll().size();
    }

    private List<AgentCronDto> loadAll() {
        return store.list(List.of(DOMAIN, BY_ID), ".json").stream()
            .map(f -> store.readOptional(List.of(DOMAIN, BY_ID, f), AgentCronDto.class))
            .flatMap(Optional::stream)
            .toList();
    }

    private boolean atomicStatusUpdate(String id, String fromStatus, String toStatus) {
        Optional<AgentCronDto> existing = findById(id);
        if (existing.isEmpty()) return false;
        AgentCronDto dto = existing.get();
        if (!fromStatus.equals(dto.status())) return false;
        updateWithLock(id, ignored -> new AgentCronDto(
            dto.id(), dto.sessionId(), dto.userId(), dto.chatId(),
            dto.channelType(), dto.channelPlatformId(), dto.cronExpression(),
            dto.timezone(), dto.runOnce(), dto.goal(), dto.nextFireTime(), dto.lastFireTime(),
            toStatus, dto.executionRole(), dto.errorReason(), dto.lastDeliveryId(),
            dto.createdAt(), Instant.now()
        ));
        return true;
    }

    private void updateWithLock(String id, UnaryOperator<AgentCronDto> updater) {
        if (id == null || id.isBlank()) {
            log.warnf("updateWithLock called with null/blank id - skipping");
            return;
        }
        Optional<AgentCronDto> existing = findById(id);
        if (existing.isPresent()) {
            AgentCronDto updated = updater.apply(existing.get());
            store.write(List.of(DOMAIN, BY_ID, id + ".json"), updated);
        }
    }

    private void addToUserIndex(String userId, String cronId) {
        UserIndex idx = loadUserIndex(userId);
        if (!idx.ids.contains(cronId)) {
            idx.ids.add(cronId);
            store.write(List.of(DOMAIN, INDICES, BY_USER, userId + ".json"), idx);
        }
    }

    private void removeFromUserIndex(String userId, String cronId) {
        UserIndex idx = loadUserIndex(userId);
        if (idx.ids.remove(cronId)) {
            store.write(List.of(DOMAIN, INDICES, BY_USER, userId + ".json"), idx);
        }
    }

    private UserIndex loadUserIndex(String userId) {
        return store.readOptional(List.of(DOMAIN, INDICES, BY_USER, userId + ".json"), UserIndex.class)
            .orElse(new UserIndex());
    }
    private static class UserIndex {
        public List<String> ids = new ArrayList<>();
    }
}
