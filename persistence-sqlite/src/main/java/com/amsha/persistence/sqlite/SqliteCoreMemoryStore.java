package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.persistence.api.CoreMemoryStorePort;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.api.TieredBackend;
import com.amsha.persistence.api.VersionConflictException;
import com.amsha.persistence.sqlite.jdbi.CoreMemoryJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@TieredBackend("sqlite")
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.SQLITE, enableIfMissing = true)
public class SqliteCoreMemoryStore implements CoreMemoryStorePort {

    private final Jdbi jdbi;

    @Inject
    public SqliteCoreMemoryStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SqliteCoreMemoryStore() {
        this(null);
    }

    @Override
    public List<CoreMemoryBlockDto> read(String userId) {
        return jdbi.withExtension(CoreMemoryJdbiDao.class, dao -> dao.findByUser(userId));
    }

    @Override
    public void upsert(String userId, String blockKey, String content, int expectedVersion) {
        String blockId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        int rows = jdbi.inTransaction(handle -> {
            var dao = handle.attach(CoreMemoryJdbiDao.class);
            return dao.upsert(blockId, userId, blockKey, content, expectedVersion, now);
        });

        if (rows == 0) {
            Optional<CoreMemoryBlockDto> existing =
                    jdbi.withExtension(CoreMemoryJdbiDao.class, dao -> dao.findByKey(userId, blockKey));

            int actualVersion = existing.map(CoreMemoryBlockDto::version).orElse(-1);
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
