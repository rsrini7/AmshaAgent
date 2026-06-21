package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.core.memory.tiered.TieredBackend;
import com.amsha.persistence.api.RecallMemoryStorePort;
import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.RecallSearchQuery;
import com.amsha.persistence.postgres.jdbi.RecallMemoryJdbiDao;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@TieredBackend("postgres")
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
public class PostgresRecallMemoryStore implements RecallMemoryStorePort {

    private static final Logger log = Logger.getLogger(PostgresRecallMemoryStore.class);

    private final Jdbi jdbi;

    @Inject
    public PostgresRecallMemoryStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresRecallMemoryStore() {
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
        log.debugf(
                "[PostgresRecallMemoryStore] purgeExpired not yet implemented "
                        + "for userId=%s, retentionDays=%d — Phase 12 deliverable",
                userId, retentionDays);
    }
}
