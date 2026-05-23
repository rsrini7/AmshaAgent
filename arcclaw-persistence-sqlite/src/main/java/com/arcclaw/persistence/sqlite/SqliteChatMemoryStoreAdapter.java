package com.arcclaw.persistence.sqlite;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.persistence.api.ChatMemoryDto;
import com.arcclaw.persistence.api.ChatMemoryStorePort;
import com.arcclaw.persistence.sqlite.jdbi.ChatMemoryJdbiDao;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import org.jdbi.v3.core.Jdbi;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteChatMemoryStoreAdapter implements ChatMemoryStorePort {

    private final Jdbi jdbi;

    @Inject
    public SqliteChatMemoryStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SqliteChatMemoryStoreAdapter() {
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
