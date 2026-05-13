package com.arcclaw.core.audit;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.core.domain.model.AuditEvent;
import com.arcclaw.persistence.api.AuditEventDto;
import com.arcclaw.persistence.api.AuditEventStorePort;
import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class AuditEventRepository {

    @Inject
    AuditEventStorePort auditStore;

    @PostConstruct
    void warmupQueryCache() {
        // Force HQL compilation at startup - now handled by adapter
    }

    @Inject
    AuditHashUtil hashUtil;

    @Inject
    SessionAuditSequencer sequencer;

    /**
     * Persists an AuditEvent and computes its HMAC chain fields.
     */
    @Transactional
    public void record(AuditEvent event) {
        if (event.getIdempotencyKey() != null) {
            if (auditStore.countByIdempotencyKey(event.getIdempotencyKey()) > 0) {
                return;
            }
        }

        ReentrantLock lock = sequencer.lockFor(event.getSessionId());
        lock.lock();
        try {
            long currentMax = auditStore.getMaxSequence(event.getSessionId());
            event.setSequence(currentMax + 1);

            String prevHash = findLatestHash(event.getSessionId());
            hashUtil.calculateChainFields(event, prevHash);

            validateBeforePersist(event);
            auditStore.save(toDto(event));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Defensive pre-persist validation.
     * Catches any null-escape from hash utilities before hitting the DB NOT NULL constraint.
     */
    public void validateBeforePersist(AuditEvent event) {
        if (event.getSessionId() == null || event.getSessionId().isBlank())
            throw new IllegalStateException("AuditEvent.sessionId must not be null/blank");
        if (event.getPrevHash() == null || event.getPrevHash().isBlank())
            throw new IllegalStateException("AuditEvent.prevHash must not be null - genesis hash failed");
        if (event.getHmacHash() == null || event.getHmacHash().isBlank())
            throw new IllegalStateException("AuditEvent.hmacHash must not be null - HMAC failed");
        if (event.getSequence() < 0)
            throw new IllegalStateException("AuditEvent.sequence must be >= 0");
    }

    /**
     * Retrieves the latest HMAC hash for a specific session to continue the chain.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public String findLatestHash(String sessionId) {
        return auditStore.findLatestHash(sessionId).orElse(null);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<AuditEvent> findBySessionId(String sessionId) {
        return auditStore.findBySessionId(sessionId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public boolean hasEventInSession(String sessionId, SecurityEventType eventType) {
        return auditStore.countEventsByType(sessionId, eventType.name()) > 0;
    }

    private AuditEventEntity toEntity(AuditEventDto dto) {
        AuditEventEntity entity = new AuditEventEntity();
        entity.setId(dto.id());
        entity.setSessionId(dto.sessionId());
        entity.setUserId(dto.userId());
        entity.setAgentRole(dto.agentRole());
        entity.setGoalHash(dto.goalHash());
        entity.setOutcome(dto.outcome() != null ? AuditOutcome.valueOf(dto.outcome()) : null);
        entity.setEventType(dto.eventType() != null ? SecurityEventType.valueOf(dto.eventType()) : null);
        entity.setEventDetail(dto.eventDetail());
        entity.setInputTokens(dto.inputTokens());
        entity.setOutputTokens(dto.outputTokens());
        entity.setUsdCost(dto.usdCost());
        entity.setTimestamp(dto.timestamp());
        entity.setIterationsUsed(dto.iterationsUsed());
        entity.setAppVersion(dto.appVersion());
        entity.setIdempotencyKey(dto.idempotencyKey());
        entity.setSequence(dto.sequence());
        entity.setHmacHash(dto.hmacHash());
        entity.setPrevHash(dto.prevHash());
        return entity;
    }

    private AuditEventDto toDto(AuditEvent entity) {
        return new AuditEventDto(
            entity.getId(),
            entity.getSessionId(),
            entity.getUserId(),
            entity.getAgentRole(),
            entity.getGoalHash(),
            entity.getOutcome() != null ? entity.getOutcome().name() : null,
            entity.getEventType() != null ? entity.getEventType().name() : null,
            entity.getEventDetail(),
            entity.getInputTokens(),
            entity.getOutputTokens(),
            entity.getUsdCost(),
            entity.getTimestamp(),
            entity.getIterationsUsed(),
            entity.getAppVersion(),
            entity.getIdempotencyKey(),
            entity.getSequence(),
            entity.getHmacHash(),
            entity.getPrevHash()
        );
    }
}
