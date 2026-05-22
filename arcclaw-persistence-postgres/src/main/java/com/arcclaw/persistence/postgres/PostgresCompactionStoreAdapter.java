package com.arcclaw.persistence.postgres;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import com.arcclaw.persistence.common.entity.CompactionSummaryEntity;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresCompactionStoreAdapter implements CompactionStorePort {

    @Inject
    EntityManager em;

    @Inject
    Instance<PostgresCompactionStoreAdapter> self;

    @Override
    @Transactional
    public Optional<CompactionSummaryDto> findLatestBySessionId(String sessionId) {
        CompactionSummaryEntity entity = em.find(CompactionSummaryEntity.class, sessionId);
        if (entity != null) {
            em.refresh(entity);
            return Optional.of(entityToDto(entity));
        }
        return Optional.empty();
    }

    @Override
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void upsert(CompactionSummaryDto dto) {
        self.get().doUpsertSummary(dto);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doUpsertSummary(CompactionSummaryDto dto) {
        CompactionSummaryEntity existing = em.find(CompactionSummaryEntity.class, dto.sessionId());

        if (existing != null) {
            existing.setSummary(dto.summary());
            existing.setMessagesCompacted(dto.messagesCompacted());
            existing.setCompactedAt(Instant.now());
            if (dto.userId() != null) existing.setUserId(dto.userId());
            em.merge(existing);
        } else {
            CompactionSummaryEntity newEntity = new CompactionSummaryEntity(
                    dto.sessionId(), dto.summary(), Instant.now(), 
                    dto.messagesCompacted(), dto.userId(), null);
            em.persist(newEntity);
        }
    }

    @Override
    @Transactional
    public void deleteBySessionId(String sessionId) {
        em.createQuery("DELETE FROM CompactionSummaryEntity c WHERE c.sessionId = :sid")
          .setParameter("sid", sessionId)
          .executeUpdate();
    }

    @Override
    @Transactional
    public List<CompactionSummaryDto> search(String userId, String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        
        @SuppressWarnings("unchecked")
        List<CompactionSummaryEntity> results = em.createNativeQuery("""
                SELECT cs.* FROM compaction_summary cs
                WHERE cs.user_id = ?1
                AND to_tsvector('english', cs.summary) @@ plainto_tsquery('english', ?2)
                LIMIT ?3
                """, CompactionSummaryEntity.class)
                .setParameter(1, userId)
                .setParameter(2, query)
                .setParameter(3, limit)
                .getResultList();

        return results.stream().map(this::entityToDto).toList();
    }

    private CompactionSummaryDto entityToDto(CompactionSummaryEntity entity) {
        return new CompactionSummaryDto(
            entity.getSessionId(),
            entity.getSummary(),
            entity.getCompactedAt(),
            entity.getMessagesCompacted(),
            entity.getUserId()
        );
    }
}
