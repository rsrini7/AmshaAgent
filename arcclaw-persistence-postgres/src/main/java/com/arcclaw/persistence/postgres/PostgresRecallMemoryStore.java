package com.arcclaw.persistence.postgres;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.memory.tiered.TieredBackend;
import com.arcclaw.persistence.api.RecallMemoryStorePort;
import com.arcclaw.persistence.api.RecallMessageDto;
import com.arcclaw.persistence.api.RecallSearchQuery;
import com.arcclaw.persistence.postgres.jdbi.RecallMemoryJdbiDao;
import io.quarkus.arc.properties.IfBuildProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

@ApplicationScoped
@TieredBackend("postgres")
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
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
        jdbi.useExtension(RecallMemoryJdbiDao.class,
            dao -> dao.insertOrIgnore(message));
    }

    @Override
    public void insertBatch(List<RecallMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        jdbi.useExtension(RecallMemoryJdbiDao.class,
            dao -> dao.insertBatch(messages));
    }

    @Override
    public List<RecallMessageDto> search(RecallSearchQuery query) {
        return jdbi.withExtension(RecallMemoryJdbiDao.class, dao -> 
            dao.search(
                query.userId(), 
                query.isCrossSession() ? null : query.sessionId(), 
                query.query(), 
                query.includeSummaries(), 
                query.limit()
            )
        );
    }

    @Override
    public Optional<RecallMessageDto> getById(String messageId) {
        return Optional.empty();
    }

    @Override
    public void purgeExpired(String userId, int retentionDays) {
        log.debugf("[PostgresRecallMemoryStore] purgeExpired not yet implemented " +
                   "for userId=%s, retentionDays=%d — Phase 12 deliverable",
                   userId, retentionDays);
    }
}
