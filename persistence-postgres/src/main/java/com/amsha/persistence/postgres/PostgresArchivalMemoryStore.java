package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalMemoryStorePort;
import com.amsha.persistence.api.ArchivalSearchQuery;
import com.amsha.persistence.api.TieredBackend;
import com.amsha.persistence.postgres.jdbi.ArchivalMemoryJdbiDao;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@TieredBackend("postgres")
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
public class PostgresArchivalMemoryStore implements ArchivalMemoryStorePort {

    private static final Logger log = Logger.getLogger(PostgresArchivalMemoryStore.class);

    private final Jdbi jdbi;

    @Inject
    public PostgresArchivalMemoryStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresArchivalMemoryStore() {
        this(null);
    }

    @Override
    public void upsert(ArchivalChunkDto chunk) {
        jdbi.useExtension(ArchivalMemoryJdbiDao.class, dao -> dao.upsert(chunk));
        log.debugf(
                "[Archival] Upserted chunk %s (user=%s, type=%s)", chunk.chunkId(), chunk.userId(), chunk.chunkType());
    }

    @Override
    public List<ArchivalChunkDto> search(ArchivalSearchQuery query) {
        boolean chunkTypesEmpty =
                query.chunkTypes() == null || query.chunkTypes().isEmpty();
        List<String> chunkTypes = chunkTypesEmpty ? List.of() : List.copyOf(query.chunkTypes());

        List<ArchivalChunkDto> results = jdbi.withExtension(ArchivalMemoryJdbiDao.class, dao -> {
            if (chunkTypesEmpty) {
                return dao.searchAllTypes(query.userId(), query.query(), query.limit());
            }
            return dao.searchByTypes(query.userId(), query.query(), query.limit(), chunkTypes);
        });
        log.debugf("[Archival] Search user=%s query='%s' → %d results", query.userId(), query.query(), results.size());
        return results;
    }

    @Override
    public Optional<ArchivalChunkDto> getById(String chunkId) {
        return jdbi.withExtension(ArchivalMemoryJdbiDao.class, dao -> dao.getById(chunkId));
    }

    @Override
    public void incrementAccessCount(String chunkId) {
        jdbi.useExtension(ArchivalMemoryJdbiDao.class, dao -> dao.incrementAccessCount(chunkId));
    }

    @Override
    public void incrementAccessCounts(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return;
        jdbi.useExtension(ArchivalMemoryJdbiDao.class, dao -> dao.incrementAccessCounts(chunkIds));
        log.debugf("[Archival] Incremented access count for %d chunks", chunkIds.size());
    }

    @Override
    public void delete(String chunkId) {
        jdbi.useExtension(ArchivalMemoryJdbiDao.class, dao -> dao.delete(chunkId));
        log.debugf("[Archival] Deleted chunk %s", chunkId);
    }

    @Override
    public void evictLowConfidence(double threshold, int minAgeDays) {
        jdbi.useExtension(ArchivalMemoryJdbiDao.class, dao -> dao.evictLowConfidence(threshold, minAgeDays));
        log.infof("[Archival] Evicted low-confidence chunks (threshold=%.2f, minAge=%d days)", threshold, minAgeDays);
    }

    /**
     * Returns the total number of archival chunks for a given user.
     */
    public long countByUserId(String userId) {
        return jdbi.withExtension(ArchivalMemoryJdbiDao.class, dao -> dao.countByUserId(userId));
    }

    /**
     * Lists archival chunks for a given user with pagination.
     */
    public List<ArchivalChunkDto> listByUserId(String userId, int limit, int offset) {
        return jdbi.withExtension(ArchivalMemoryJdbiDao.class, dao -> dao.listByUserId(userId, limit, offset));
    }
}
