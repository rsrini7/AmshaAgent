package com.arcclaw.persistence.postgres;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.audit.AuditEventEntity;
import com.arcclaw.core.audit.AuditOutcome;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.domain.model.AuditEvent;
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
        return em.createQuery("SELECT e FROM AuditEvent e WHERE e.sessionId = :sessionId ORDER BY e.sequence ASC", AuditEventEntity.class)
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
                e.getId(), e.getSessionId(), e.getUserId(), e.getAgentRole(), e.getGoalHash(),
                e.getOutcome() != null ? e.getOutcome().name() : null,
                e.getEventType() != null ? e.getEventType().name() : null,
                e.getEventDetail(), e.getInputTokens(), e.getOutputTokens(), e.getUsdCost(),
                e.getTimestamp(), e.getIterationsUsed(), e.getAppVersion(), e.getIdempotencyKey(),
                e.getSequence(), e.getHmacHash(), e.getPrevHash()
        );
    }
}
