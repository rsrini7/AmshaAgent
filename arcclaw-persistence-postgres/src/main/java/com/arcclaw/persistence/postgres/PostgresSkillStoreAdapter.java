package com.arcclaw.persistence.postgres;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.persistence.api.SkillDto;
import com.arcclaw.persistence.api.SkillStorePort;
import com.arcclaw.persistence.postgres.jdbi.SkillJdbiDao;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import org.jdbi.v3.core.Jdbi;

/**
 * PostgreSQL implementation of SkillStorePort using JDBI.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresSkillStoreAdapter implements SkillStorePort {

    private final Jdbi jdbi;

    @Inject
    public PostgresSkillStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresSkillStoreAdapter() {
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
