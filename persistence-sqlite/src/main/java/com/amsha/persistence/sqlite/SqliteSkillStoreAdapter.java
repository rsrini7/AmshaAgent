package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.SkillDto;
import com.amsha.persistence.api.SkillStorePort;
import com.amsha.persistence.sqlite.jdbi.SkillJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteSkillStoreAdapter implements SkillStorePort {

    private final Jdbi jdbi;

    @Inject
    public SqliteSkillStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SqliteSkillStoreAdapter() {
        this(null);
    }

    @Override
    public void save(SkillDto dto) {
        jdbi.useExtension(SkillJdbiDao.class, dao -> dao.save(dto));
    }

    @Override
    public List<SkillDto> findAll() {
        return jdbi.withExtension(SkillJdbiDao.class, SkillJdbiDao::findAll);
    }

    @Override
    public Optional<SkillDto> findByName(String name) {
        return jdbi.withExtension(SkillJdbiDao.class, dao -> dao.findByName(name));
    }

    @Override
    public boolean deleteById(String id) {
        return jdbi.withExtension(SkillJdbiDao.class, dao -> dao.deleteById(id));
    }

    @Override
    public long deleteAll() {
        return jdbi.withExtension(SkillJdbiDao.class, SkillJdbiDao::deleteAll);
    }
}
