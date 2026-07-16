package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.memory.tiered.TieredBackend;
import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.persistence.api.CoreMemoryStorePort;
import com.amsha.persistence.api.VersionConflictException;
import com.amsha.persistence.postgres.jdbi.CoreMemoryJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@TieredBackend("postgres")
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
public class PostgresCoreMemoryStore implements CoreMemoryStorePort {

    private final Jdbi jdbi;

    @Inject
    public PostgresCoreMemoryStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresCoreMemoryStore() {
        this(null);
    }

    @Override
    public List<CoreMemoryBlockDto> read(String userId) {
        return jdbi.withExtension(CoreMemoryJdbiDao.class, dao -> dao.findByUser(userId));
    }

    @Override
    public void upsert(String userId, String blockKey, String content, int expectedVersion) {
        int rows = jdbi.inTransaction(handle -> handle.attach(CoreMemoryJdbiDao.class)
                .upsert(UUID.randomUUID().toString(), userId, blockKey, content, expectedVersion, Instant.now()));
        if (rows == 0) {
            int actualVersion = jdbi.withExtension(CoreMemoryJdbiDao.class, dao -> dao.findByKey(userId, blockKey))
                    .map(CoreMemoryBlockDto::version)
                    .orElse(-1);
            throw new VersionConflictException(blockKey, expectedVersion, actualVersion);
        }
    }

    @Override
    public void delete(String userId, String blockKey) {
        jdbi.useExtension(CoreMemoryJdbiDao.class, dao -> dao.delete(userId, blockKey));
    }

    @Override
    public Optional<CoreMemoryBlockDto> get(String userId, String blockKey) {
        return jdbi.withExtension(CoreMemoryJdbiDao.class, dao -> dao.findByKey(userId, blockKey));
    }
}
