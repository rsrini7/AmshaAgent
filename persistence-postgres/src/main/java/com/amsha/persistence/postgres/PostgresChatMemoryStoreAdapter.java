package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.ChatMemoryDto;
import com.amsha.persistence.api.ChatMemoryStorePort;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.postgres.jdbi.ChatMemoryJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * PostgreSQL implementation of ChatMemoryStorePort using JDBI.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.POSTGRES)
public class PostgresChatMemoryStoreAdapter implements ChatMemoryStorePort {

    private final Jdbi jdbi;

    @Inject
    public PostgresChatMemoryStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresChatMemoryStoreAdapter() {
        this(null);
    }

    @Override
    public void save(ChatMemoryDto dto) {
        jdbi.useExtension(ChatMemoryJdbiDao.class, dao -> dao.save(dto));
    }

    @Override
    public Optional<ChatMemoryDto> findById(String memoryId) {
        return jdbi.withExtension(ChatMemoryJdbiDao.class, dao -> dao.findById(memoryId));
    }

    @Override
    public void deleteById(String memoryId) {
        jdbi.useExtension(ChatMemoryJdbiDao.class, dao -> dao.deleteById(memoryId));
    }

    @Override
    public List<Long> getTimestamps(String memoryId) {
        return Collections.emptyList();
    }
}
