package com.arcclaw.core.audit;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

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
        // Force HQL compilation at startup — now handled by adapter
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
        if (event.idempotencyKey != null) {
            if (auditStore.countByIdempotencyKey(event.idempotencyKey) > 0) {
                // Already recorded in a previous (retried) attempt. Skip to ensure logical idempotency.
                return;
            }
        }

        ReentrantLock lock = sequencer.lockFor(event.sessionId);
        lock.lock();
        try {
            // Step 1: assign monotonic sequence
            long currentMax = auditStore.getMaxSequence(event.sessionId);
            event.sequence = currentMax + 1;

            // Step 2 & 3: Compute chain fields via centralized utility
            String prevHash = findLatestHash(event.sessionId);
            hashUtil.calculateChainFields(event, prevHash);

            // Step 4: validate and persist
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
        if (event.sessionId == null || event.sessionId.isBlank())
            throw new IllegalStateException("AuditEvent.sessionId must not be null/blank");
        if (event.prevHash == null || event.prevHash.isBlank())
            throw new IllegalStateException("AuditEvent.prevHash must not be null — genesis hash failed");
        if (event.hmacHash == null || event.hmacHash.isBlank())
            throw new IllegalStateException("AuditEvent.hmacHash must not be null — HMAC failed");
        if (event.sequence < 0)
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

    private AuditEvent toEntity(AuditEventDto dto) {
        AuditEvent entity = new AuditEvent();
        entity.id = dto.id();
        entity.sessionId = dto.sessionId();
        entity.userId = dto.userId();
        entity.agentRole = dto.agentRole();
        entity.goalHash = dto.goalHash();
        entity.outcome = dto.outcome() != null ? AuditOutcome.valueOf(dto.outcome()) : null;
        entity.eventType = dto.eventType() != null ? SecurityEventType.valueOf(dto.eventType()) : null;
        entity.eventDetail = dto.eventDetail();
        entity.inputTokens = dto.inputTokens();
        entity.outputTokens = dto.outputTokens();
        entity.usdCost = dto.usdCost();
        entity.timestamp = dto.timestamp();
        entity.iterationsUsed = dto.iterationsUsed();
        entity.appVersion = dto.appVersion();
        entity.idempotencyKey = dto.idempotencyKey();
        entity.sequence = dto.sequence();
        entity.hmacHash = dto.hmacHash();
        entity.prevHash = dto.prevHash();
        return entity;
    }

    private AuditEventDto toDto(AuditEvent entity) {
        return new AuditEventDto(
            entity.id,
            entity.sessionId,
            entity.userId,
            entity.agentRole,
            entity.goalHash,
            entity.outcome != null ? entity.outcome.name() : null,
            entity.eventType != null ? entity.eventType.name() : null,
            entity.eventDetail,
            entity.inputTokens,
            entity.outputTokens,
            entity.usdCost,
            entity.timestamp,
            entity.iterationsUsed,
            entity.appVersion,
            entity.idempotencyKey,
            entity.sequence,
            entity.hmacHash,
            entity.prevHash
        );
    }
}
