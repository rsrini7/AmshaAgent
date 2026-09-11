package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStatus;
import com.amsha.persistence.api.CronStorePort;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
@SuppressWarnings("java:S1192")
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
        return loadAll().stream().filter(dto -> userId.equals(dto.userId())).toList();
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
    public synchronized boolean claimForFire(String id, String deliveryId) {
        if (id == null || id.isBlank() || deliveryId == null || deliveryId.isBlank()) return false;
        AgentCronDto dto = findById(id).orElse(null);
        if (dto == null || !CronStatus.ACTIVE.name().equals(dto.status())) return false;
        store.write(
                List.of(DOMAIN, BY_ID, id + ".json"),
                withStatusAndDelivery(dto, CronStatus.RUNNING.name(), deliveryId));
        return true;
    }

    @Override
    public synchronized boolean claimForResume(String id, String deliveryId) {
        if (id == null || id.isBlank() || deliveryId == null || deliveryId.isBlank()) return false;
        AgentCronDto dto = findById(id).orElse(null);
        if (dto == null) return false;
        boolean runningOwner =
                CronStatus.RUNNING.name().equals(dto.status()) && deliveryId.equals(dto.lastDeliveryId());
        if (!CronStatus.AWAITING_APPROVAL.name().equals(dto.status())
                && !CronStatus.AWAITING_RETRY.name().equals(dto.status())
                && !runningOwner) return false;
        store.write(
                List.of(DOMAIN, BY_ID, id + ".json"),
                withStatusAndDelivery(dto, CronStatus.RUNNING.name(), deliveryId));
        return true;
    }

    @Override
    public void releaseClaimToActive(String id) {
        atomicStatusUpdate(id, CronStatus.RUNNING.name(), CronStatus.ACTIVE.name());
    }

    @Override
    public void markFired(String id, Instant lastFireTime, Instant nextFireTime) {
        updateWithLock(
                id,
                dto -> copyWith(
                        dto,
                        nextFireTime,
                        lastFireTime,
                        CronStatus.ACTIVE.name(),
                        dto.errorReason(),
                        dto.lastDeliveryId()));
    }

    @Override
    public void markCompleted(String id, Instant lastFireTime) {
        updateWithLock(
                id,
                dto -> copyWith(
                        dto, null, lastFireTime, CronStatus.COMPLETED.name(), dto.errorReason(), dto.lastDeliveryId()));
    }

    @Override
    public void markAwaitingApproval(String id) {
        updateWithLock(
                id,
                dto -> new AgentCronDto(
                        dto.id(),
                        dto.sessionId(),
                        dto.userId(),
                        dto.chatId(),
                        dto.channelType(),
                        dto.channelPlatformId(),
                        dto.cronExpression(),
                        dto.timezone(),
                        dto.runOnce(),
                        dto.goal(),
                        dto.nextFireTime(),
                        dto.lastFireTime(),
                        CronStatus.AWAITING_APPROVAL.name(),
                        dto.executionRole(),
                        dto.errorReason(),
                        dto.lastDeliveryId(),
                        dto.createdAt(),
                        Instant.now()));
    }

    @Override
    public void markRunning(String id) {
        updateWithLock(
                id,
                dto -> new AgentCronDto(
                        dto.id(),
                        dto.sessionId(),
                        dto.userId(),
                        dto.chatId(),
                        dto.channelType(),
                        dto.channelPlatformId(),
                        dto.cronExpression(),
                        dto.timezone(),
                        dto.runOnce(),
                        dto.goal(),
                        dto.nextFireTime(),
                        dto.lastFireTime(),
                        CronStatus.RUNNING.name(),
                        dto.executionRole(),
                        dto.errorReason(),
                        dto.lastDeliveryId(),
                        dto.createdAt(),
                        Instant.now()));
    }

    @Override
    public void setDeliveryId(String id, String deliveryId) {
        updateWithLock(
                id,
                dto -> copyWith(
                        dto, dto.nextFireTime(), dto.lastFireTime(), dto.status(), dto.errorReason(), deliveryId));
    }

    @Override
    public void markFailed(String id, String errorReason) {
        updateWithLock(
                id,
                dto -> copyWith(
                        dto,
                        dto.nextFireTime(),
                        dto.lastFireTime(),
                        CronStatus.FAILED.name(),
                        errorReason,
                        dto.lastDeliveryId()));
    }

    @Override
    public void markSkipped(String id, Instant nextFireTime, Instant now) {
        updateWithLock(
                id,
                dto -> copyWith(
                        dto,
                        nextFireTime,
                        dto.lastFireTime(),
                        CronStatus.SKIPPED.name(),
                        dto.errorReason(),
                        dto.lastDeliveryId()));
    }

    @Override
    public synchronized void reclaimStaleCrons(Instant threshold) {
        for (AgentCronDto dto : loadAll()) {
            if (CronStatus.RUNNING.name().equals(dto.status())) {
                Optional<AgentCronDto> curOpt = findById(dto.id());
                if (curOpt.isPresent()) {
                    AgentCronDto cur = curOpt.get();
                    if (CronStatus.RUNNING.name().equals(cur.status())) {
                        Instant ref = cur.updatedAt() != null ? cur.updatedAt() : cur.createdAt();
                        if (ref != null && (ref.isBefore(threshold) || ref.equals(threshold))) {
                            updateWithLock(
                                    cur.id(), ignored -> withStatusAndDelivery(cur, CronStatus.ACTIVE.name(), null));
                        }
                    }
                }
            }
        }
    }

    @Override
    public synchronized boolean renewLease(String id, String deliveryId) {
        if (id == null || id.isBlank() || deliveryId == null || deliveryId.isBlank()) return false;
        Optional<AgentCronDto> opt = findById(id);
        if (opt.isEmpty()) return false;
        AgentCronDto dto = opt.get();
        if (CronStatus.RUNNING.name().equals(dto.status()) && deliveryId.equals(dto.lastDeliveryId())) {
            store.write(List.of(DOMAIN, BY_ID, id + ".json"), withStatus(dto, dto.status()));
            return true;
        }
        return false;
    }

    @Override
    public synchronized boolean markCompletedIfOwner(String id, String deliveryId, Instant lastFireTime) {
        if (id == null || id.isBlank() || deliveryId == null || deliveryId.isBlank()) return false;
        Optional<AgentCronDto> opt = findById(id);
        if (opt.isEmpty()) return false;
        AgentCronDto dto = opt.get();
        if (!deliveryId.equals(dto.lastDeliveryId())
                || !CronStatus.RUNNING.name().equals(dto.status())) {
            return false;
        }
        markCompleted(id, lastFireTime);
        return true;
    }

    @Override
    public synchronized boolean markFiredIfOwner(
            String id, String deliveryId, Instant lastFireTime, Instant nextFireTime) {
        if (id == null || id.isBlank() || deliveryId == null || deliveryId.isBlank()) return false;
        Optional<AgentCronDto> opt = findById(id);
        if (opt.isEmpty()) return false;
        AgentCronDto dto = opt.get();
        if (!deliveryId.equals(dto.lastDeliveryId())
                || !CronStatus.RUNNING.name().equals(dto.status())) {
            return false;
        }
        markFired(id, lastFireTime, nextFireTime);
        return true;
    }

    @Override
    public synchronized boolean markAwaitingApprovalIfOwner(String id, String deliveryId) {
        if (id == null || id.isBlank() || deliveryId == null || deliveryId.isBlank()) return false;
        Optional<AgentCronDto> opt = findById(id);
        if (opt.isEmpty()) return false;
        AgentCronDto dto = opt.get();
        if (!deliveryId.equals(dto.lastDeliveryId())
                || !CronStatus.RUNNING.name().equals(dto.status())) {
            return false;
        }
        markAwaitingApproval(id);
        return true;
    }

    @Override
    public synchronized boolean markAwaitingRetryIfOwner(String id, String deliveryId) {
        if (id == null || id.isBlank() || deliveryId == null || deliveryId.isBlank()) return false;
        Optional<AgentCronDto> opt = findById(id);
        if (opt.isEmpty()) return false;
        AgentCronDto dto = opt.get();
        if (!deliveryId.equals(dto.lastDeliveryId())
                || !CronStatus.RUNNING.name().equals(dto.status())) {
            return false;
        }
        updateWithLock(id, cur -> withStatus(cur, CronStatus.AWAITING_RETRY.name()));
        return true;
    }

    @Override
    public synchronized boolean markFailedIfOwner(String id, String deliveryId, String errorReason) {
        if (id == null || id.isBlank() || deliveryId == null || deliveryId.isBlank()) return false;
        Optional<AgentCronDto> opt = findById(id);
        if (opt.isEmpty()) return false;
        AgentCronDto dto = opt.get();
        boolean validStatus = CronStatus.RUNNING.name().equals(dto.status())
                || CronStatus.AWAITING_APPROVAL.name().equals(dto.status())
                || CronStatus.AWAITING_RETRY.name().equals(dto.status());
        if (!deliveryId.equals(dto.lastDeliveryId()) || !validStatus) {
            return false;
        }
        markFailed(id, errorReason);
        return true;
    }

    @Override
    public synchronized boolean markFailedIfStatus(String id, String expectedStatus, String errorReason) {
        if (id == null || id.isBlank()) return false;
        Optional<AgentCronDto> opt = findById(id);
        if (opt.isEmpty()) return false;
        AgentCronDto dto = opt.get();
        if (expectedStatus != null && !expectedStatus.equals(dto.status())) return false;
        markFailed(id, errorReason);
        return true;
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
            all = all.stream().filter(dto -> status.equals(dto.status())).toList();
        }
        all = all.stream()
                .sorted(Comparator.comparing(
                        AgentCronDto::nextFireTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        int end = Math.min(offset + limit, all.size());
        if (offset >= all.size()) return List.of();
        return all.subList(offset, end);
    }

    @Override
    public long countAll(String status) {
        if (status != null && !status.isBlank()) {
            return loadAll().stream().filter(dto -> status.equals(dto.status())).count();
        }
        return loadAll().size();
    }

    @Override
    public List<AgentCronDto> findByUserId(String userId, String status, int offset, int limit) {
        List<AgentCronDto> all = loadAll().stream()
                .filter(dto -> userId.equals(dto.userId()))
                .filter(dto -> status == null || status.isBlank() || status.equals(dto.status()))
                .sorted(Comparator.comparing(
                        AgentCronDto::nextFireTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        int end = Math.min(offset + limit, all.size());
        if (offset >= all.size()) return List.of();
        return all.subList(offset, end);
    }

    @Override
    public long countByUserId(String userId, String status) {
        return loadAll().stream()
                .filter(dto -> userId.equals(dto.userId()))
                .filter(dto -> status == null || status.isBlank() || status.equals(dto.status()))
                .count();
    }

    private List<AgentCronDto> loadAll() {
        return store.list(List.of(DOMAIN, BY_ID), ".json").stream()
                .map(f -> store.readOptional(List.of(DOMAIN, BY_ID, f), AgentCronDto.class))
                .flatMap(Optional::stream)
                .toList();
    }

    private synchronized boolean atomicStatusUpdate(String id, String fromStatus, String toStatus) {
        Optional<AgentCronDto> existing = findById(id);
        if (existing.isEmpty()) return false;
        AgentCronDto dto = existing.get();
        if (!fromStatus.equals(dto.status())) return false;
        updateWithLock(id, ignored -> withStatus(dto, toStatus));
        return true;
    }

    private synchronized void updateWithLock(String id, UnaryOperator<AgentCronDto> updater) {
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

    private static AgentCronDto copyWith(
            AgentCronDto dto,
            Instant nextFireTime,
            Instant lastFireTime,
            String status,
            String errorReason,
            String deliveryId) {
        return new AgentCronDto(
                dto.id(),
                dto.sessionId(),
                dto.userId(),
                dto.chatId(),
                dto.channelType(),
                dto.channelPlatformId(),
                dto.cronExpression(),
                dto.timezone(),
                dto.runOnce(),
                dto.goal(),
                nextFireTime,
                lastFireTime,
                status,
                dto.executionRole(),
                errorReason,
                deliveryId,
                dto.createdAt(),
                Instant.now());
    }

    private static AgentCronDto withStatus(AgentCronDto dto, String status) {
        return copyWith(dto, dto.nextFireTime(), dto.lastFireTime(), status, dto.errorReason(), dto.lastDeliveryId());
    }

    private static AgentCronDto withStatusAndDelivery(AgentCronDto dto, String status, String deliveryId) {
        return copyWith(dto, dto.nextFireTime(), dto.lastFireTime(), status, dto.errorReason(), deliveryId);
    }

    @SuppressWarnings("EffectivelyPrivate") // public field needed for Jackson serialization
    private static class UserIndex {
        public List<String> ids = new ArrayList<>();
    }
}
