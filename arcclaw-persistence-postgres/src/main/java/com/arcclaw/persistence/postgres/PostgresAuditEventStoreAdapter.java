package com.arcclaw.persistence.postgres;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.audit.AuditOutcome;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.domain.model.AuditEvent;
import com.arcclaw.persistence.api.AuditEventDto;
import com.arcclaw.persistence.api.AuditEventStorePort;
import com.arcclaw.persistence.common.entity.AuditEventEntity;

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
        return max != null ? max : 0;
    }

    @Override
    public Optional<AuditEventDto> findById(UUID id) {
        if (id == null) return Optional.empty();
        AuditEventEntity entity = em.find(AuditEventEntity.class, id);
        return Optional.ofNullable(entity).map(this::toDto);
    }

    @Override
    public List<AuditEventDto> findAll(String sessionId, String eventType, Instant from, Instant to, int offset, int limit) {
        StringBuilder jpql = new StringBuilder("SELECT e FROM AuditEvent e WHERE 1=1");
        if (sessionId != null && !sessionId.isBlank()) {
            jpql.append(" AND e.sessionId = :sessionId");
        }
        if (eventType != null && !eventType.isBlank()) {
            jpql.append(" AND e.eventType = :eventType");
        }
        if (from != null) {
            jpql.append(" AND e.timestamp >= :from");
        }
        if (to != null) {
            jpql.append(" AND e.timestamp <= :to");
        }
        jpql.append(" ORDER BY e.timestamp DESC");

        var query = em.createQuery(jpql.toString(), AuditEventEntity.class);
        if (sessionId != null && !sessionId.isBlank()) {
            query.setParameter("sessionId", sessionId);
        }
        if (eventType != null && !eventType.isBlank()) {
            query.setParameter("eventType", SecurityEventType.valueOf(eventType));
        }
        if (from != null) {
            query.setParameter("from", from);
        }
        if (to != null) {
            query.setParameter("to", to);
        }
        query.setFirstResult(offset);
        query.setMaxResults(limit);
        return query.getResultStream().map(this::toDto).toList();
    }

    @Override
    public long countAll(String sessionId, String eventType, Instant from, Instant to) {
        StringBuilder jpql = new StringBuilder("SELECT COUNT(e) FROM AuditEvent e WHERE 1=1");
        if (sessionId != null && !sessionId.isBlank()) {
            jpql.append(" AND e.sessionId = :sessionId");
        }
        if (eventType != null && !eventType.isBlank()) {
            jpql.append(" AND e.eventType = :eventType");
        }
        if (from != null) {
            jpql.append(" AND e.timestamp >= :from");
        }
        if (to != null) {
            jpql.append(" AND e.timestamp <= :to");
        }

        var query = em.createQuery(jpql.toString(), Long.class);
        if (sessionId != null && !sessionId.isBlank()) {
            query.setParameter("sessionId", sessionId);
        }
        if (eventType != null && !eventType.isBlank()) {
            query.setParameter("eventType", SecurityEventType.valueOf(eventType));
        }
        if (from != null) {
            query.setParameter("from", from);
        }
        if (to != null) {
            query.setParameter("to", to);
        }
        return query.getSingleResult();
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

