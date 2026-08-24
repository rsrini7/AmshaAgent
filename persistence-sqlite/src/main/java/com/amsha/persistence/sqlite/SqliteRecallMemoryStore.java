package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.RecallMemoryStorePort;
import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.RecallSearchQuery;
import com.amsha.persistence.api.TieredBackend;
import com.amsha.persistence.sqlite.jdbi.RecallMemoryJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@TieredBackend("sqlite")
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
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
        return jdbi.withExtension(RecallMemoryJdbiDao.class, dao -> dao.getById(messageId));
    }

    @Override
    public void purgeExpired(String userId, int retentionDays) {
        jdbi.useExtension(RecallMemoryJdbiDao.class, dao -> dao.purgeExpired(userId, retentionDays));
    }

    @Override
    public void purgeExpiredAll(int retentionDays) {
        jdbi.useExtension(RecallMemoryJdbiDao.class, dao -> dao.purgeExpiredAll(retentionDays));
    }

    /**
     * Returns the total number of recall messages for a given user.
     */
    public long countByUserId(String userId) {
        return jdbi.withExtension(RecallMemoryJdbiDao.class, dao -> dao.countByUserId(userId));
    }
}
