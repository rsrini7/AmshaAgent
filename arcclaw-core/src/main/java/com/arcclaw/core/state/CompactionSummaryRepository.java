package com.arcclaw.core.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class CompactionSummaryRepository {

    @Inject
    EntityManager em;

    @Transactional
    public Optional<CompactionSummaryEntity> findLatestBySessionId(String sessionId) {
        CompactionSummaryEntity entity = em.find(CompactionSummaryEntity.class, sessionId);
        return Optional.ofNullable(entity);
    }

    @Transactional
    public void upsertSummary(String sessionId, String userId, String summaryText, int compactedCount) {
        CompactionSummaryEntity existing = em.find(CompactionSummaryEntity.class, sessionId);

        if (existing != null) {
            existing.summary = summaryText;
            existing.messagesCompacted = compactedCount;
            existing.compactedAt = Instant.now();
            if (userId != null) existing.userId = userId;
            em.merge(existing);
        } else {
            CompactionSummaryEntity newEntity = new CompactionSummaryEntity(
                    sessionId, summaryText, Instant.now(), compactedCount, userId, null);
            em.persist(newEntity);
        }
        em.flush(); // Ensure main is written before FTS sync

        // Manual FTS Sync: DELETE then INSERT (standalone pattern)
        em.createNativeQuery("DELETE FROM fts.compaction_summary_fts WHERE sessionId = ?1")
                .setParameter(1, sessionId)
                .executeUpdate();

        em.createNativeQuery("INSERT INTO fts.compaction_summary_fts(sessionId, summary) VALUES (?1, ?2)")
                .setParameter(1, sessionId)
                .setParameter(2, summaryText)
                .executeUpdate();
    }

    @Transactional
    public void deleteBySessionId(String sessionId) {
        // 1. Delete main entry
        em.createQuery(
            "DELETE FROM CompactionSummaryEntity c " +
            "WHERE c.sessionId = :sid")
          .setParameter("sid", sessionId)
          .executeUpdate();

        // 2. Delete FTS entry in isolated schema (manual sync)
        em.createNativeQuery("DELETE FROM fts.compaction_summary_fts WHERE sessionId = ?1")
          .setParameter(1, sessionId)
          .executeUpdate();
    }

    /**
     * Full-text search across compaction summaries for a specific user.
     * Uses SQLite FTS5 MATCH with BM25 ranking.
     *
     * @param userId tenant isolation key (from ScopedValue, never LLM params)
     * @param query  search keywords
     * @param limit  max results to return
     * @return ranked list of matching compaction summaries
     */
    @Transactional
    public List<CompactionSummaryEntity> searchMemory(String userId, String query, int limit) {
        // Sanitize query to prevent FTS5 syntax errors
        String sanitized = query.replaceAll("[^a-zA-Z0-9 ]", "").trim();
        if (sanitized.isBlank()) return List.of();
        
        // Use native query to leverage FTS5 MATCH operator on the isolated 'fts' schema
        // Join on sessionId since standalone table handles it
        @SuppressWarnings("unchecked")
        List<CompactionSummaryEntity> results =  em.createNativeQuery("""
                SELECT cs.* FROM compaction_summary cs
                JOIN fts.compaction_summary_fts fts_v ON cs.sessionId = fts_v.sessionId
                WHERE fts_v.summary MATCH ?1
                AND cs.user_id = ?2
                ORDER BY rank
                LIMIT ?3
                """, CompactionSummaryEntity.class)
                .setParameter(1, sanitized)
                .setParameter(2, userId)
                .setParameter(3, limit)
                .getResultList();

        return results;
    }
}
