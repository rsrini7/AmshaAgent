package com.arcclaw.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.arcclaw.core.memory.tiered.TieredBackend;
import com.arcclaw.persistence.api.RecallMemoryStorePort;
import com.arcclaw.persistence.api.RecallMessageDto;
import com.arcclaw.persistence.api.RecallSearchQuery;
import com.arcclaw.persistence.sqlite.jdbi.RecallMemoryJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@TieredBackend("sqlite")
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteRecallMemoryStore implements RecallMemoryStorePort {

    private final Jdbi jdbi;

    @Inject
    public SqliteRecallMemoryStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SqliteRecallMemoryStore() {
        this(null);
    }

    @Override
    public void insertOrIgnore(RecallMessageDto message) {
        jdbi.useExtension(RecallMemoryJdbiDao.class, dao -> dao.insertOrIgnore(message));
    }

    @Override
    public void insertBatch(List<RecallMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        jdbi.useExtension(RecallMemoryJdbiDao.class, dao -> dao.insertBatch(messages));
    }

    @Override
    public List<RecallMessageDto> search(RecallSearchQuery query) {
        return jdbi.withExtension(
                RecallMemoryJdbiDao.class,
                dao -> dao.search(
                        query.userId(),
                        query.isCrossSession() ? null : query.sessionId(),
                        query.query(),
                        query.includeSummaries(),
                        query.limit()));
    }

    @Override
    public Optional<RecallMessageDto> getById(String messageId) {
        return Optional.empty();
    }

    @Override
    public void purgeExpired(String userId, int retentionDays) {
        // No-op: recall messages are ephemeral per-session — no server-side expiry needed.
    }
}
