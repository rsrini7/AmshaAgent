package com.arcclaw.core.test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.CronStorePort;

@Alternative
@Priority(1)
@ApplicationScoped
public class MockCronStorePort implements CronStorePort {

    private final Map<String, AgentCronDto> store = new ConcurrentHashMap<>();

    @Override 
    public List<AgentCronDto> findDueCrons() { 
        return store.values().stream()
                .filter(c -> "ACTIVE".equals(c.status()) && c.nextFireTime().isBefore(Instant.now()))
                .collect(Collectors.toList());
    }

    @Override 
    public List<AgentCronDto> findByUserId(String userId) { 
        return store.values().stream()
                .filter(c -> userId.equals(c.userId()))
                .collect(Collectors.toList());
    }

    @Override 
    public List<AgentCronDto> findAwaitingApprovalByUserId(String userId) { 
        return store.values().stream()
                .filter(c -> userId.equals(c.userId()) && "AWAITING_APPROVAL".equals(c.status()))
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentCronDto> findAllAwaitingApproval() {
        return store.values().stream()
                .filter(c -> "AWAITING_APPROVAL".equals(c.status()))
                .collect(Collectors.toList());
    }

    @Override 
    public Optional<AgentCronDto> findById(String id) { 
        return Optional.ofNullable(store.get(id)); 
    }

    @Override 
    public boolean claimForFire(String id) { 
        AgentCronDto c = store.get(id);
        if (c != null && "ACTIVE".equals(c.status())) {
            store.put(id, copyWithStatus(c, "RUNNING"));
            return true;
        }
        return false;
    }

    @Override 
    public void releaseClaimToActive(String id) {
        AgentCronDto c = store.get(id);
        if (c != null && "RUNNING".equals(c.status())) {
            store.put(id, copyWithStatus(c, "ACTIVE"));
        }
    }

    @Override 
    public void markFired(String id, Instant lastFireTime, Instant nextFireTime) {
        AgentCronDto c = store.get(id);
        if (c != null) {
            store.put(id, new AgentCronDto(
                c.id(), c.sessionId(), c.userId(), c.chatId(), c.channelType(), c.channelPlatformId(),
                c.cronExpression(), c.timezone(), c.runOnce(), c.goal(),
                nextFireTime, lastFireTime, "ACTIVE", c.executionRole(), c.errorReason(),
                c.lastDeliveryId(), c.createdAt(), Instant.now()
            ));
        }
    }

    @Override 
    public void markCompleted(String id, Instant lastFireTime) {
        AgentCronDto c = store.get(id);
        if (c != null) {
            store.put(id, new AgentCronDto(
                c.id(), c.sessionId(), c.userId(), c.chatId(), c.channelType(), c.channelPlatformId(),
                c.cronExpression(), c.timezone(), c.runOnce(), c.goal(),
                null, lastFireTime, "COMPLETED", c.executionRole(), c.errorReason(),
                c.lastDeliveryId(), c.createdAt(), Instant.now()
            ));
        }
    }

    @Override 
    public void markAwaitingApproval(String id) {
        AgentCronDto c = store.get(id);
        if (c != null) {
            store.put(id, copyWithStatus(c, "AWAITING_APPROVAL"));
        }
    }

    @Override
    public void markRunning(String id) {
        AgentCronDto c = store.get(id);
        if (c != null) {
            store.put(id, copyWithStatus(c, "RUNNING"));
        }
    }

    @Override 
    public void setDeliveryId(String id, String deliveryId) {
        AgentCronDto c = store.get(id);
        if (c != null) {
            store.put(id, new AgentCronDto(
                c.id(), c.sessionId(), c.userId(), c.chatId(), c.channelType(), c.channelPlatformId(),
                c.cronExpression(), c.timezone(), c.runOnce(), c.goal(),
                c.nextFireTime(), c.lastFireTime(), c.status(), c.executionRole(), c.errorReason(),
                deliveryId, c.createdAt(), Instant.now()
            ));
        }
    }

    @Override 
    public void markFailed(String id, String errorReason) {
        AgentCronDto c = store.get(id);
        if (c != null) {
            store.put(id, new AgentCronDto(
                c.id(), c.sessionId(), c.userId(), c.chatId(), c.channelType(), c.channelPlatformId(),
                c.cronExpression(), c.timezone(), c.runOnce(), c.goal(),
                null, c.lastFireTime(), "FAILED", c.executionRole(), errorReason,
                c.lastDeliveryId(), c.createdAt(), Instant.now()
            ));
        }
    }

    @Override 
    public void markSkipped(String id, Instant nextFireTime, Instant now) {
        AgentCronDto c = store.get(id);
        if (c != null) {
            store.put(id, new AgentCronDto(
                c.id(), c.sessionId(), c.userId(), c.chatId(), c.channelType(), c.channelPlatformId(),
                c.cronExpression(), c.timezone(), c.runOnce(), c.goal(),
                nextFireTime, c.lastFireTime(), "SKIPPED", c.executionRole(), c.errorReason(),
                c.lastDeliveryId(), c.createdAt(), now
            ));
        }
    }

    @Override 
    public void reclaimStaleCrons(Instant threshold) {
        store.values().stream()
                .filter(c -> "RUNNING".equals(c.status()) && c.updatedAt().isBefore(threshold))
                .forEach(c -> store.put(c.id(), copyWithStatus(c, "ACTIVE")));
    }

    @Override 
    public void save(AgentCronDto dto) {
        store.put(dto.id(), dto);
    }

    @Override 
    public int deleteByIdAndUserId(String id, String userId) { 
        AgentCronDto c = store.get(id);
        if (c != null && userId.equals(c.userId())) {
            store.remove(id);
            return 1;
        }
        return 0;
    }

    @Override 
    public long deleteAllByUserId(String userId) { 
        long count = store.values().stream().filter(c -> userId.equals(c.userId())).count();
        store.entrySet().removeIf(e -> userId.equals(e.getValue().userId()));
        return count;
    }

    @Override 
    public long deleteAllBySessionId(String sessionId) { 
        long count = store.values().stream().filter(c -> sessionId.equals(c.sessionId())).count();
        store.entrySet().removeIf(e -> sessionId.equals(e.getValue().sessionId()));
        return count;
    }

    private AgentCronDto copyWithStatus(AgentCronDto c, String status) {
        return new AgentCronDto(
            c.id(), c.sessionId(), c.userId(), c.chatId(), c.channelType(), c.channelPlatformId(),
            c.cronExpression(), c.timezone(), c.runOnce(), c.goal(),
            c.nextFireTime(), c.lastFireTime(), status, c.executionRole(), c.errorReason(),
            c.lastDeliveryId(), c.createdAt(), Instant.now()
        );
    }
}
