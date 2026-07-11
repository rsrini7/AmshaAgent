package com.amsha.selfevolve.store.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.MemoryRecordType;
import com.amsha.selfevolve.store.ExtractedMemoryStorePort;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * PostgreSQL implementation of extracted memory store.
 * Uses JDBI with ON CONFLICT DO NOTHING for DB-enforced deduplication.
 */
@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
public class PostgresExtractedMemoryStore implements ExtractedMemoryStorePort {

    private static final Logger log = Logger.getLogger(PostgresExtractedMemoryStore.class);

    @Inject
    javax.sql.DataSource dataSource;

    @Override
    public int insertBatch(List<ExtractedMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return 0;
        }
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        int[] results = jdbi.withExtension(PostgresExtractedMemoryJdbiDao.class, dao -> dao.insertBatch(memories));
        int persisted = 0;
        for (int r : results) {
            if (r > 0) persisted++;
        }
        log.debugf("[SelfEvolve] Persisted %d/%d extracted memories (Postgres)", persisted, memories.size());
        return persisted;
    }

    @Override
    public List<ExtractedMemory> getBySession(String sessionId, int limit) {
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        return jdbi.withExtension(PostgresExtractedMemoryJdbiDao.class, dao -> dao.getBySession(sessionId, limit));
    }

    @Override
    public List<ExtractedMemory> getByUser(String userId, int limit) {
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        return jdbi.withExtension(PostgresExtractedMemoryJdbiDao.class, dao -> dao.getByUser(userId, limit));
    }

    @Override
    public List<ExtractedMemory> getByType(MemoryRecordType recordType, int limit) {
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        return jdbi.withExtension(PostgresExtractedMemoryJdbiDao.class, dao -> dao.getByType(recordType.name(), limit));
    }

    @Override
    public List<String> listUserIds(int limit) {
        int sqlLimit = limit <= 0 ? Integer.MAX_VALUE : limit;
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        return jdbi.withExtension(PostgresExtractedMemoryJdbiDao.class, dao -> dao.listUserIds(sqlLimit));
    }

    @Override
    public boolean existsByHash(String sessionId, String contentHash) {
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        return jdbi.withExtension(
                PostgresExtractedMemoryJdbiDao.class, dao -> dao.existsByHash(sessionId, contentHash));
    }

    @Override
    public int purgeExpired(int retentionDays) {
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        return jdbi.withExtension(PostgresExtractedMemoryJdbiDao.class, dao -> dao.purgeExpired(retentionDays));
    }
}
