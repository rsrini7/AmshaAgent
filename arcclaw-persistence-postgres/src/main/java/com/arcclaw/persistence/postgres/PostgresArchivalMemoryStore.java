package com.arcclaw.persistence.postgres;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.memory.tiered.TieredBackend;
import com.arcclaw.persistence.api.ArchivalChunkDto;
import com.arcclaw.persistence.api.ArchivalMemoryStorePort;
import com.arcclaw.persistence.api.ArchivalSearchQuery;
import com.arcclaw.persistence.postgres.jdbi.ArchivalMemoryJdbiDao;
import io.quarkus.arc.properties.IfBuildProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

@ApplicationScoped
@TieredBackend("postgres")
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
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
        jdbi.useExtension(ArchivalMemoryJdbiDao.class,
            dao -> dao.upsert(chunk));
    }

    @Override
    public List<ArchivalChunkDto> search(ArchivalSearchQuery query) {
        boolean chunkTypesEmpty = query.chunkTypes() == null || query.chunkTypes().isEmpty();
        List<String> chunkTypes = chunkTypesEmpty ? List.of("") : List.copyOf(query.chunkTypes());

        return jdbi.withExtension(ArchivalMemoryJdbiDao.class, dao ->
            dao.search(
                query.userId(),
                query.query(),
                query.limit(),
                chunkTypesEmpty,
                chunkTypes
            )
        );
    }

    @Override
    public Optional<ArchivalChunkDto> getById(String chunkId) {
        return jdbi.withExtension(ArchivalMemoryJdbiDao.class,
            dao -> dao.getById(chunkId));
    }

    @Override
    public void incrementAccessCount(String chunkId) {
        jdbi.useExtension(ArchivalMemoryJdbiDao.class,
            dao -> dao.incrementAccessCount(chunkId));
    }

    @Override
    public void delete(String chunkId) {
        jdbi.useExtension(ArchivalMemoryJdbiDao.class,
            dao -> dao.delete(chunkId));
    }

    @Override
    public void evictLowConfidence(double threshold, int minAgeDays) {
        log.debugf("evictLowConfidence not yet implemented (threshold=%.2f, minAgeDays=%d) — Phase 12 deliverable",
            threshold, minAgeDays);
    }
}
