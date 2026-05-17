package com.arcclaw.persistence.sqlite;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.persistence.api.CronRunDto;
import com.arcclaw.persistence.api.CronRunStorePort;
import com.arcclaw.persistence.common.entity.CronRunEntity;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SqliteCronRunStoreAdapter implements CronRunStorePort {

    private static final Logger log = Logger.getLogger(SqliteCronRunStoreAdapter.class);

    @Inject
    EntityManager em;

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void save(CronRunDto dto) {
        CronRunEntity entity = em.find(CronRunEntity.class, dto.id());
        if (entity == null) {
            entity = new CronRunEntity();
            entity.id = dto.id();
            entity.createdAt = Instant.now();
        }
        entity.cronId = dto.cronId();
        entity.sessionId = dto.sessionId();
        entity.status = dto.status();
        entity.startedAt = dto.startedAt();
        entity.finishedAt = dto.finishedAt();
        entity.errorReason = dto.errorReason();
        entity.iterationsUsed = dto.iterationsUsed();
        entity.inputTokens = dto.inputTokens();
        entity.outputTokens = dto.outputTokens();
        entity.usdCost = dto.usdCost();
        em.merge(entity);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRED)
    public Optional<CronRunDto> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        CronRunEntity entity = em.find(CronRunEntity.class, id);
        return Optional.ofNullable(entity).map(this::toDto);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRED)
    public List<CronRunDto> findByCronId(String cronId, int offset, int limit) {
        em.clear();
        return em.createQuery(
                "SELECT r FROM CronRunEntity r WHERE r.cronId = :cronId ORDER BY r.startedAt DESC",
                CronRunEntity.class)
         .setParameter("cronId", cronId)
         .setFirstResult(offset)
         .setMaxResults(limit)
         .getResultList()
         .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRED)
    public long countByCronId(String cronId) {
        em.clear();
        return em.createQuery(
                "SELECT COUNT(r) FROM CronRunEntity r WHERE r.cronId = :cronId", Long.class)
         .setParameter("cronId", cronId)
         .getSingleResult();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRED)
    public List<CronRunDto> findAll(String status, int offset, int limit) {
        em.clear();
        if (status != null && !status.isBlank()) {
            return em.createQuery(
                    "SELECT r FROM CronRunEntity r WHERE r.status = :status ORDER BY r.startedAt DESC",
                    CronRunEntity.class)
             .setParameter("status", status)
             .setFirstResult(offset)
             .setMaxResults(limit)
             .getResultList()
             .stream().map(this::toDto).collect(Collectors.toList());
        }
        return em.createQuery(
                "SELECT r FROM CronRunEntity r ORDER BY r.startedAt DESC",
                CronRunEntity.class)
         .setFirstResult(offset)
         .setMaxResults(limit)
         .getResultList()
         .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRED)
    public long countAll(String status) {
        em.clear();
        if (status != null && !status.isBlank()) {
            return em.createQuery(
                    "SELECT COUNT(r) FROM CronRunEntity r WHERE r.status = :status", Long.class)
             .setParameter("status", status)
             .getSingleResult();
        }
        return em.createQuery("SELECT COUNT(r) FROM CronRunEntity r", Long.class)
         .getSingleResult();
    }

    private CronRunDto toDto(CronRunEntity e) {
        return new CronRunDto(
            e.id, e.cronId, e.sessionId, e.status,
            e.startedAt, e.finishedAt, e.errorReason,
            e.iterationsUsed, e.inputTokens, e.outputTokens,
            e.usdCost, e.createdAt
        );
    }
}
