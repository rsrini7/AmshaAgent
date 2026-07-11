package com.amsha.selfevolve.store.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.MemoryRecordType;
import com.amsha.selfevolve.store.ExtractedMemoryStorePort;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * SQLite implementation of extracted memory store.
 * Uses JDBI with INSERT OR IGNORE for DB-enforced deduplication.
 */
@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteExtractedMemoryStore implements ExtractedMemoryStorePort {

    private static final Logger log = Logger.getLogger(SqliteExtractedMemoryStore.class);

    @Inject
    Jdbi jdbi;

    @Override
    public int insertBatch(List<ExtractedMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return 0;
        }
        int[] results = jdbi.withExtension(ExtractedMemoryJdbiDao.class, dao -> dao.insertBatch(memories));
        int persisted = 0;
        for (int r : results) {
            if (r > 0) persisted++;
        }
        log.debugf("[SelfEvolve] Persisted %d/%d extracted memories", persisted, memories.size());
        return persisted;
    }

    @Override
    public List<ExtractedMemory> getBySession(String sessionId, int limit) {
        return jdbi.withExtension(ExtractedMemoryJdbiDao.class, dao -> dao.getBySession(sessionId, limit));
    }

    @Override
    public List<ExtractedMemory> getByUser(String userId, int limit) {
        return jdbi.withExtension(ExtractedMemoryJdbiDao.class, dao -> dao.getByUser(userId, limit));
    }

    @Override
    public List<ExtractedMemory> getByType(MemoryRecordType recordType, int limit) {
        return jdbi.withExtension(ExtractedMemoryJdbiDao.class, dao -> dao.getByType(recordType.name(), limit));
    }

    @Override
    public List<String> listUserIds(int limit) {
        int sqlLimit = limit <= 0 ? -1 : limit;
        return jdbi.withExtension(ExtractedMemoryJdbiDao.class, dao -> dao.listUserIds(sqlLimit));
    }

    @Override
    public boolean existsByHash(String sessionId, String contentHash) {
        return jdbi.withExtension(ExtractedMemoryJdbiDao.class, dao -> dao.existsByHash(sessionId, contentHash));
    }

    @Override
    public int purgeExpired(int retentionDays) {
        return jdbi.withExtension(ExtractedMemoryJdbiDao.class, dao -> dao.purgeExpired(retentionDays));
    }
}
