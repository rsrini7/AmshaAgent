package com.amsha.selfevolve.store.postgres;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.store.DirectiveStorePort;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * PostgreSQL implementation of directive store.
 */
@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
public class PostgresDirectiveStore implements DirectiveStorePort {

    private static final Logger log = Logger.getLogger(PostgresDirectiveStore.class);

    @Inject
    DataSource dataSource;

    @Override
    public int insert(SynthesizedDirective directive) {
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        int rows = jdbi.withExtension(PostgresDirectiveJdbiDao.class, dao -> dao.insert(directive));
        if (rows > 0) {
            log.debugf(
                    "[SelfEvolve] Inserted directive %s (state=%s, confidence=%.2f)",
                    directive.id(), directive.state(), directive.confidence());
        }
        return rows;
    }

    @Override
    public void update(SynthesizedDirective directive) {
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        jdbi.useExtension(PostgresDirectiveJdbiDao.class, dao -> dao.update(directive));
        log.debugf("[SelfEvolve] Updated directive %s (state=%s)", directive.id(), directive.state());
    }

    @Override
    public List<SynthesizedDirective> getInjectable(String userId, int limit) {
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        return jdbi.withExtension(PostgresDirectiveJdbiDao.class, dao -> dao.getInjectable(userId, limit));
    }

    @Override
    public List<SynthesizedDirective> getAll(String userId, int limit) {
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        return jdbi.withExtension(PostgresDirectiveJdbiDao.class, dao -> dao.getAll(userId, limit));
    }

    @Override
    public SynthesizedDirective getById(String directiveId) {
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        return jdbi.withExtension(PostgresDirectiveJdbiDao.class, dao -> dao.getById(directiveId));
    }

    @Override
    public List<SynthesizedDirective> getByState(DirectiveState state, int limit) {
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        return jdbi.withExtension(PostgresDirectiveJdbiDao.class, dao -> dao.getByState(state.name(), limit));
    }

    @Override
    public boolean delete(String directiveId) {
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        int rows = jdbi.withExtension(PostgresDirectiveJdbiDao.class, dao -> dao.deleteById(directiveId));
        log.debugf("[SelfEvolve] Deleted directive %s (found=%b)", directiveId, rows > 0);
        return rows > 0;
    }

    @Override
    public int purgeExpired(int retentionDays) {
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        int purged = jdbi.withExtension(PostgresDirectiveJdbiDao.class, dao -> dao.purgeExpired(retentionDays));
        if (purged > 0) {
            log.infof("[SelfEvolve] Purged %d expired directives (retention=%d days)", purged, retentionDays);
        }
        return purged;
    }
}
