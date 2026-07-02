package com.amsha.selfevolve.store.sqlite;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.store.DirectiveStorePort;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * SQLite implementation of directive store.
 */
@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteDirectiveStore implements DirectiveStorePort {

    private static final Logger log = Logger.getLogger(SqliteDirectiveStore.class);

    @Inject
    DataSource dataSource;

    @Override
    public int insert(SynthesizedDirective directive) {
        Jdbi jdbi = Jdbi.create(dataSource);
        return jdbi.withExtension(DirectiveJdbiDao.class, dao -> dao.insert(directive));
    }

    @Override
    public void update(SynthesizedDirective directive) {
        Jdbi jdbi = Jdbi.create(dataSource);
        jdbi.useExtension(DirectiveJdbiDao.class, dao -> dao.update(directive));
    }

    @Override
    public List<SynthesizedDirective> getInjectable(String userId, int limit) {
        Jdbi jdbi = Jdbi.create(dataSource);
        return jdbi.withExtension(DirectiveJdbiDao.class, dao -> dao.getInjectable(userId, limit));
    }

    @Override
    public List<SynthesizedDirective> getAll(String userId, int limit) {
        Jdbi jdbi = Jdbi.create(dataSource);
        return jdbi.withExtension(DirectiveJdbiDao.class, dao -> dao.getAll(userId, limit));
    }

    @Override
    public SynthesizedDirective getById(String directiveId) {
        Jdbi jdbi = Jdbi.create(dataSource);
        return jdbi.withExtension(DirectiveJdbiDao.class, dao -> dao.getById(directiveId));
    }

    @Override
    public List<SynthesizedDirective> getByState(DirectiveState state, int limit) {
        Jdbi jdbi = Jdbi.create(dataSource);
        return jdbi.withExtension(DirectiveJdbiDao.class, dao -> dao.getByState(state.name(), limit));
    }

    @Override
    public boolean delete(String directiveId) {
        Jdbi jdbi = Jdbi.create(dataSource);
        int rows = jdbi.withExtension(DirectiveJdbiDao.class, dao -> dao.deleteById(directiveId));
        return rows > 0;
    }

    @Override
    public int purgeExpired(int retentionDays) {
        Jdbi jdbi = Jdbi.create(dataSource);
        return jdbi.withExtension(DirectiveJdbiDao.class, dao -> dao.purgeExpired(retentionDays));
    }
}
