package com.arcclaw.persistence.postgres;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import com.arcclaw.persistence.postgres.jdbi.CompactionJdbiDao;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import org.jdbi.v3.core.Jdbi;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresCompactionStoreAdapter implements CompactionStorePort {

    private final Jdbi jdbi;

    @Inject
    public PostgresCompactionStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresCompactionStoreAdapter() {
        this(null);
    }

    @Override
    public Optional<CompactionSummaryDto> findLatestBySessionId(String sessionId) {
        return jdbi.withExtension(CompactionJdbiDao.class, dao -> dao.findById(sessionId));
    }

    @Override
    public void upsert(CompactionSummaryDto dto) {
        jdbi.useExtension(CompactionJdbiDao.class, dao -> dao.upsert(
            dto.sessionId(),
            dto.summary(),
            Instant.now(),
            dto.messagesCompacted(),
            dto.userId()
        ));
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        jdbi.useExtension(CompactionJdbiDao.class, dao -> dao.deleteBySessionId(sessionId));
    }

    @Override
    public List<CompactionSummaryDto> search(String userId, String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        return jdbi.withExtension(CompactionJdbiDao.class, dao -> dao.search(userId, query, limit));
    }
}
