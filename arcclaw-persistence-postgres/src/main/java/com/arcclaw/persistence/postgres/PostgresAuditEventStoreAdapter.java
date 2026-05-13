package com.arcclaw.persistence.postgres;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.audit.AuditEvent;
import com.arcclaw.core.audit.AuditOutcome;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.persistence.api.AuditEventDto;
import com.arcclaw.persistence.api.AuditEventStorePort;

/**
 * PostgreSQL implementation of the AuditEventStorePort with WORM enforcement triggers.
 */
@ApplicationScoped
public class PostgresAuditEventStoreAdapter implements AuditEventStorePort {

    @Inject EntityManager em;

    @Override
    @Transactional
    public void save(AuditEventDto dto) {
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
        
        em.persist(entity);
    }

    @Override
    public Optional<String> findLatestHash(String sessionId) {
        return em.createQuery("SELECT e.hmacHash FROM AuditEvent e WHERE e.sessionId = :sessionId ORDER BY e.sequence DESC, e.timestamp DESC", String.class)
                .setParameter("sessionId", sessionId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    @Override
    public List<AuditEventDto> findBySessionId(String sessionId) {
        return em.createQuery("SELECT e FROM AuditEvent e WHERE e.sessionId = :sessionId ORDER BY e.sequence ASC", AuditEvent.class)
                .setParameter("sessionId", sessionId)
                .getResultStream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public long countByIdempotencyKey(String key) {
        return em.createQuery("SELECT COUNT(e) FROM AuditEvent e WHERE e.idempotencyKey = :key", Long.class)
                .setParameter("key", key)
                .getSingleResult();
    }

    @Override
    public long countEventsByType(String sessionId, String eventType) {
        return em.createQuery("SELECT COUNT(e) FROM AuditEvent e WHERE e.sessionId = :sessionId AND e.eventType = :type", Long.class)
                .setParameter("sessionId", sessionId)
                .setParameter("type", SecurityEventType.valueOf(eventType))
                .getSingleResult();
    }

    @Override
    public long getMaxSequence(String sessionId) {
        Long max = em.createQuery("SELECT MAX(e.sequence) FROM AuditEvent e WHERE e.sessionId = :sessionId", Long.class)
                .setParameter("sessionId", sessionId)
                .getSingleResult();
        return max != null ? max : -1;
    }

    private AuditEventDto toDto(AuditEvent e) {
        return new AuditEventDto(
                e.id, e.sessionId, e.userId, e.agentRole, e.goalHash,
                e.outcome != null ? e.outcome.name() : null,
                e.eventType != null ? e.eventType.name() : null,
                e.eventDetail, e.inputTokens, e.outputTokens, e.usdCost,
                e.timestamp, e.iterationsUsed, e.appVersion, e.idempotencyKey,
                e.sequence, e.hmacHash, e.prevHash
        );
    }
}
