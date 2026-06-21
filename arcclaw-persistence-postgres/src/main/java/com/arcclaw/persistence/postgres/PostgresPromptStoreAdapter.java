package com.arcclaw.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.PromptDto;
import com.arcclaw.persistence.api.PromptStorePort;
import com.arcclaw.persistence.postgres.jdbi.PromptJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresPromptStoreAdapter implements PromptStorePort {

    private final Jdbi jdbi;

    @Inject
    public PostgresPromptStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresPromptStoreAdapter() {
        this(null);
    }

    @Override
    public void save(PromptDto dto) {
        jdbi.useExtension(PromptJdbiDao.class, dao -> dao.save(dto));
    }

    @Override
    public List<PromptDto> findAll() {
        return jdbi.withExtension(PromptJdbiDao.class, PromptJdbiDao::findAll);
    }

    @Override
    public Optional<PromptDto> findById(String id) {
        return jdbi.withExtension(PromptJdbiDao.class, dao -> dao.findById(id));
    }

    @Override
    public Optional<PromptDto> findActive(String roleId, String sectionKey) {
        return jdbi.withExtension(PromptJdbiDao.class, dao -> dao.findActive(roleId, sectionKey));
    }

    @Override
    public List<PromptDto> findByRole(String roleId) {
        return jdbi.withExtension(PromptJdbiDao.class, dao -> dao.findByRole(roleId));
    }

    @Override
    public boolean deleteById(String id) {
        return jdbi.withExtension(PromptJdbiDao.class, dao -> dao.deleteById(id));
    }

    @Override
    public boolean deleteByRoleAndKey(String roleId, String sectionKey) {
        return jdbi.withExtension(PromptJdbiDao.class, dao -> dao.deleteByRoleAndKey(roleId, sectionKey));
    }

    @Override
    public long deleteAll() {
        return jdbi.withExtension(PromptJdbiDao.class, PromptJdbiDao::deleteAll);
    }

    @Override
    public long count() {
        return jdbi.withExtension(PromptJdbiDao.class, PromptJdbiDao::count);
    }
}
