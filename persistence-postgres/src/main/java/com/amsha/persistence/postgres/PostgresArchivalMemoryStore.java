package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.core.memory.tiered.TieredBackend;
import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalMemoryStorePort;
import com.amsha.persistence.api.ArchivalSearchQuery;
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
    }

    @Override
    public List<ArchivalChunkDto> search(ArchivalSearchQuery query) {
        boolean chunkTypesEmpty =
                query.chunkTypes() == null || query.chunkTypes().isEmpty();
        List<String> chunkTypes = chunkTypesEmpty ? List.of("") : List.copyOf(query.chunkTypes());

        return jdbi.withExtension(
                ArchivalMemoryJdbiDao.class,
                dao -> dao.search(query.userId(), query.query(), query.limit(), chunkTypesEmpty, chunkTypes));
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
    }

    @Override
    public void delete(String chunkId) {
        jdbi.useExtension(ArchivalMemoryJdbiDao.class, dao -> dao.delete(chunkId));
    }

    @Override
    public void evictLowConfidence(double threshold, int minAgeDays) {
        log.debugf(
                "evictLowConfidence not yet implemented (threshold=%.2f, minAgeDays=%d) — Phase 12 deliverable",
                threshold, minAgeDays);
    }
}
