package com.arcclaw.core.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class CompactionSummaryRepository {

    @Inject
    EntityManager em;

    @Transactional
    public Optional<CompactionSummaryEntity> findBySessionId(String sessionId) {
        CompactionSummaryEntity entity = em.find(CompactionSummaryEntity.class, sessionId);
        return Optional.ofNullable(entity);
    }

    @Transactional
    public void upsertSummary(String sessionId, String summaryText, int compactedCount) {
        CompactionSummaryEntity existing = em.find(CompactionSummaryEntity.class, sessionId);
        if (existing != null) {
            existing.summary = summaryText;
            existing.messagesCompacted += compactedCount;
            existing.compactedAt = Instant.now();
            em.merge(existing);
        } else {
            CompactionSummaryEntity newEntity = new CompactionSummaryEntity(sessionId, summaryText, Instant.now(), compactedCount);
            em.persist(newEntity);
        }
    }
}
