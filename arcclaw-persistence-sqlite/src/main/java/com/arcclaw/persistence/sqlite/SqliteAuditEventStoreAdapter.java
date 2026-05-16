package com.arcclaw.persistence.sqlite;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.audit.AuditOutcome;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.domain.model.AuditEvent;
import com.arcclaw.core.util.LogUtil;
import com.arcclaw.persistence.api.AuditEventDto;
import com.arcclaw.persistence.api.AuditEventStorePort;
import com.arcclaw.persistence.common.entity.AuditEventEntity;
import io.quarkus.arc.Unremovable;
import org.jboss.logging.Logger;

/**
 * SQLite implementation of AuditEventStorePort.
 * §1.2: All JPQL moved from core to here.
 */
@ApplicationScoped
@Unremovable
public class SqliteAuditEventStoreAdapter implements AuditEventStorePort {

    private static final Logger log = Logger.getLogger(SqliteAuditEventStoreAdapter.class);

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
        } catch (Exception e) {
            log.debugf("Warmup query failed (best effort): %s", LogUtil.rootMessage(e));
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void save(AuditEventDto dto) {
        AuditEventEntity entity = toEntity(dto);
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
                AuditEventEntity.class)
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

