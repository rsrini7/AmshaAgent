package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.CompactionStorePort;
import com.amsha.persistence.api.CompactionSummaryDto;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.postgres.jdbi.CompactionJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.POSTGRES)
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
        jdbi.useExtension(
                CompactionJdbiDao.class,
                dao -> dao.upsert(
                        dto.sessionId(), dto.summary(), Instant.now(), dto.messagesCompacted(), dto.userId()));
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
