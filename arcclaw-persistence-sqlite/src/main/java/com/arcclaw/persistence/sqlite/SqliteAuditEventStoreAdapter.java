package com.arcclaw.persistence.sqlite;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.audit.AuditEvent;
import com.arcclaw.core.audit.AuditOutcome;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.persistence.api.AuditEventDto;
import com.arcclaw.persistence.api.AuditEventStorePort;
import io.quarkus.arc.Unremovable;

/**
 * SQLite implementation of AuditEventStorePort.
 * §1.2: All JPQL moved from core to here.
 */
@ApplicationScoped
@Unremovable
public class SqliteAuditEventStoreAdapter implements AuditEventStorePort {

    @Inject
    EntityManager em;

    @PostConstruct
    void warmupQueryCache() {
        // Force HQL compilation at startup to avoid timeout during first query in native mode
        try {
            em.createQuery(
                "SELECT COUNT(a) FROM AuditEvent a WHERE a.sessionId = :sid",
                Long.class)
              .setParameter("sid", "warmup")
              .setMaxResults(1)
              .getResultList();
        } catch (Exception ignored) {
            // best effort warmup
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void save(AuditEventDto dto) {
        AuditEvent entity = toEntity(dto);
        em.persist(entity);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<String> findLatestHash(String sessionId) {
        List<String> results = em.createQuery(
                "SELECT a.hmacHash FROM AuditEvent a WHERE a.sessionId = :sid ORDER BY a.sequence DESC",
                String.class)
                .setParameter("sid", sessionId)
                .setMaxResults(1)
                .getResultList();

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<AuditEventDto> findBySessionId(String sessionId) {
        return em.createQuery(
                "SELECT a FROM AuditEvent a WHERE a.sessionId = :sid ORDER BY a.sequence ASC",
                AuditEvent.class)
                .setParameter("sid", sessionId)
                .getResultList()
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public long countByIdempotencyKey(String key) {
        return em.createQuery(
                "SELECT COUNT(a) FROM AuditEvent a WHERE a.idempotencyKey = :key",
                Long.class)
            .setParameter("key", key)
            .getSingleResult();
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public long countEventsByType(String sessionId, String eventType) {
        return em.createQuery(
                "SELECT COUNT(a) FROM AuditEvent a " +
                "WHERE a.sessionId = :sid AND a.eventType = :type",
                Long.class)
            .setParameter("sid", sessionId)
            .setParameter("type", SecurityEventType.valueOf(eventType))
            .getSingleResult();
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public long getMaxSequence(String sessionId) {
        List<Long> maxSeqResult = em.createQuery(
                "SELECT COALESCE(MAX(a.sequence), 0) FROM AuditEvent a WHERE a.sessionId = :sid",
                Long.class)
            .setParameter("sid", sessionId)
            .getResultList();
        return maxSeqResult.isEmpty() ? 0L : maxSeqResult.get(0);
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
