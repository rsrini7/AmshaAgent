package com.arcclaw.persistence.sqlite;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import com.arcclaw.persistence.common.entity.CompactionSummaryEntity;
import io.quarkus.arc.properties.IfBuildProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteCompactionStoreAdapter implements CompactionStorePort {

    private static final Logger log = Logger.getLogger(SqliteCompactionStoreAdapter.class);
    private static final Pattern FTS5_SPECIAL = Pattern.compile("([\"*^()\\-])");

    @Inject
    EntityManager em;

    @Inject
    Instance<SqliteCompactionStoreAdapter> self;

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
        self.get().syncFtsBestEffort(dto.sessionId(), dto.summary());
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

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void syncFtsBestEffort(String sessionId, String summaryText) {
        try {
            em.createNativeQuery("DELETE FROM fts.compaction_summary_fts WHERE sessionId = ?1")
                    .setParameter(1, sessionId)
                    .executeUpdate();

            em.createNativeQuery("INSERT INTO fts.compaction_summary_fts(sessionId, summary) VALUES (?1, ?2)")
                    .setParameter(1, sessionId)
                    .setParameter(2, summaryText)
                    .executeUpdate();
        } catch (Exception e) {
            log.errorf("FTS index failed for sessionId=%s: %s", sessionId, LogUtil.rootMessage(e));
        }
    }

    @Override
    @Transactional
    public void deleteBySessionId(String sessionId) {
        em.createQuery("DELETE FROM CompactionSummaryEntity c WHERE c.sessionId = :sid")
          .setParameter("sid", sessionId)
          .executeUpdate();

        em.createNativeQuery("DELETE FROM fts.compaction_summary_fts WHERE sessionId = ?1")
          .setParameter(1, sessionId)
          .executeUpdate();
    }

    @Override
    @Transactional
    public List<CompactionSummaryDto> search(String userId, String query, int limit) {
        String sanitized = sanitizeForFts5(query);
        if (sanitized == null) return List.of();
        
        @SuppressWarnings("unchecked")
        List<CompactionSummaryEntity> results = em.createNativeQuery("""
                SELECT cs.* FROM compaction_summary cs
                JOIN fts.compaction_summary_fts fts_v ON cs.session_id = fts_v.sessionId
                WHERE fts_v.summary MATCH ?1
                AND cs.user_id = ?2
                ORDER BY rank
                LIMIT ?3
                """, CompactionSummaryEntity.class)
                .setParameter(1, sanitized)
                .setParameter(2, userId)
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

    private String sanitizeForFts5(String query) {
        if (query == null || query.isBlank()) return null;
        String sanitized = query.trim().replace("\"", "\"\"");
        String escaped = FTS5_SPECIAL.matcher(sanitized).replaceAll("\"$1\"");
        if (!escaped.endsWith("*")) escaped = escaped + "*";
        return escaped.isBlank() ? null : escaped;
    }
}

