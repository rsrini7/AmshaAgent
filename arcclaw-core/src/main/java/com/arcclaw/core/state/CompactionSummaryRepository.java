package com.arcclaw.core.state;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

@ApplicationScoped
public class CompactionSummaryRepository {

    private static final Logger log = Logger.getLogger(CompactionSummaryRepository.class);

    private static final Pattern FTS5_SPECIAL = Pattern.compile("([\"*^()\\-])");

    @Inject
    EntityManager em;

    @Inject
    Instance<CompactionSummaryRepository> self;

    @Transactional
    public Optional<CompactionSummaryEntity> findLatestBySessionId(String sessionId) {
        em.clear(); // A16 — Force read through L1 cache to avoid stale values during CLI polling
        CompactionSummaryEntity entity = em.find(CompactionSummaryEntity.class, sessionId);
        return Optional.ofNullable(entity);
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void upsertSummary(String sessionId, String userId, String summaryText, int compactedCount) {
        // Main write unit
        self.get().doUpsertSummary(sessionId, userId, summaryText, compactedCount);

        // Manual FTS Sync (isolated tx to prevent indexing failure from rolling back main write)
        self.get().syncFtsBestEffort(sessionId, summaryText);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doUpsertSummary(String sessionId, String userId, String summaryText, int compactedCount) {
        CompactionSummaryEntity existing = em.find(CompactionSummaryEntity.class, sessionId);

        if (existing != null) {
            existing.setSummary(summaryText);
            existing.setMessagesCompacted(compactedCount);
            existing.setCompactedAt(Instant.now());
            if (userId != null) existing.setUserId(userId);
            em.merge(existing);
        } else {
            CompactionSummaryEntity newEntity = new CompactionSummaryEntity(
                    sessionId, summaryText, Instant.now(), compactedCount, userId, null);
            em.persist(newEntity);
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void syncFtsBestEffort(String sessionId, String summaryText) {
        try {
            // Manual FTS Sync: DELETE then INSERT (standalone pattern)
            em.createNativeQuery("DELETE FROM fts.compaction_summary_fts WHERE sessionId = ?1")
                    .setParameter(1, sessionId)
                    .executeUpdate();

            em.createNativeQuery("INSERT INTO fts.compaction_summary_fts(sessionId, summary) VALUES (?1, ?2)")
                    .setParameter(1, sessionId)
                    .setParameter(2, summaryText)
                    .executeUpdate();
        } catch (Exception e) {
            log.errorf("FTS index failed for sessionId=%s — non-fatal for main write: %s", 
                    sessionId, e.getMessage());
        }
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
     * <p><strong>D2.3/F26 Multi-Tenant Tradeoff:</strong>
     * Currently, the MATCH runs globally on `fts_v.summary`, meaning BM25 IDF scores
     * are polluted by term frequencies across ALL users' data. Filtering by `cs.user_id = ?2`
     * happens AFTER scoring. Before scaling to multi-tenant/SaaS workloads, this must be
     * redesigned (e.g., using a per-tenant FTS shadow table or injecting tenant_id into
     * an unindexed FTS column so MATCH can filter at the index level).
     *
     * @param userId tenant isolation key (from ScopedValue, never LLM params)
     * @param query  search keywords
     * @param limit  max results to return
     * @return ranked list of matching compaction summaries
     */
    @Transactional
    public List<CompactionSummaryEntity> searchMemory(String userId, String query, int limit) {
        // Sanitize query to prevent FTS5 syntax errors
        String sanitized = sanitizeForFts5(query);
        if (sanitized == null) {
            log.trace("Search query is blank or null — returning empty list");
            return List.of();
        }
        
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

    private String sanitizeForFts5(String query) {
        if (query == null || query.isBlank()) return null;
        // Escape FTS5 special characters by wrapping them in double quotes.
        // Also double-up any existing double quotes for safety.
        String sanitized = query.trim().replace("\"", "\"\"");
        String escaped = FTS5_SPECIAL.matcher(sanitized).replaceAll("\"$1\"");
        
        // Append * for prefix matching. This is critical for CJK languages (like Japanese)
        // where tokens aren't space-separated, and for improved partial-word recall.
        if (!escaped.endsWith("*")) {
            escaped = escaped + "*";
        }
        
        return escaped.isBlank() ? null : escaped;
    }
}
