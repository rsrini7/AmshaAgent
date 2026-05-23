package com.arcclaw.persistence.sqlite;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import com.arcclaw.persistence.sqlite.jdbi.CompactionJdbiDao;
import io.quarkus.arc.properties.IfBuildProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

@ApplicationScoped
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteCompactionStoreAdapter implements CompactionStorePort {

    private static final Logger log = Logger.getLogger(SqliteCompactionStoreAdapter.class);
    private static final Pattern FTS5_SPECIAL = Pattern.compile("([\"*^()\\-])");

    private final Jdbi jdbi;

    @Inject
    public SqliteCompactionStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SqliteCompactionStoreAdapter() {
        this(null);
    }

    @Override
    public Optional<CompactionSummaryDto> findLatestBySessionId(String sessionId) {
        return jdbi.withExtension(CompactionJdbiDao.class, dao -> dao.findById(sessionId));
    }

    @Override
    public void upsert(CompactionSummaryDto dto) {
        jdbi.useTransaction(handle -> {
            var dao = handle.attach(CompactionJdbiDao.class);
            dao.upsert(dto.sessionId(), dto.summary(), dto.compactedAt(), dto.messagesCompacted(), dto.userId());
            syncFtsBestEffort(handle, dto.sessionId(), dto.summary());
        });
    }

    private void syncFtsBestEffort(Handle handle, String sessionId, String summaryText) {
        try {
            var dao = handle.attach(CompactionJdbiDao.class);
            dao.deleteFtsBySessionId(sessionId);
            dao.upsertFts(sessionId, summaryText);
        } catch (Exception e) {
            log.errorf("FTS index failed for sessionId=%s: %s", sessionId, LogUtil.rootMessage(e));
        }
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        jdbi.useTransaction(handle -> {
            var dao = handle.attach(CompactionJdbiDao.class);
            dao.deleteBySessionId(sessionId);
            try {
                dao.deleteFtsBySessionId(sessionId);
            } catch (Exception e) {
                log.debugf("FTS delete failed (may not exist): %s", LogUtil.rootMessage(e));
            }
        });
    }

    @Override
    public List<CompactionSummaryDto> search(String userId, String query, int limit) {
        String sanitized = sanitizeForFts5(query);
        if (sanitized == null) return List.of();
        return jdbi.withExtension(CompactionJdbiDao.class, dao -> dao.search(sanitized, userId, limit));
    }

    private String sanitizeForFts5(String query) {
        if (query == null || query.isBlank()) return null;
        String sanitized = query.trim().replace("\"", "\"\"");
        String escaped = FTS5_SPECIAL.matcher(sanitized).replaceAll("\"$1\"");
        if (!escaped.endsWith("*")) escaped = escaped + "*";
        return escaped.isBlank() ? null : escaped;
    }
}
