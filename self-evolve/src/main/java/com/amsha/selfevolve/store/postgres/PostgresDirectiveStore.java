package com.amsha.selfevolve.store.postgres;

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
 * PostgreSQL implementation of directive store.
 */
@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
public class PostgresDirectiveStore implements DirectiveStorePort {

    private static final Logger log = Logger.getLogger(PostgresDirectiveStore.class);

    /** Injected Jdbi carries the PostgreSQL argument and column mappers. */
    @Inject
    Jdbi jdbi;

    @Override
    public int insert(SynthesizedDirective directive) {
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
        jdbi.useExtension(PostgresDirectiveJdbiDao.class, dao -> dao.update(directive));
        log.debugf("[SelfEvolve] Updated directive %s (state=%s)", directive.id(), directive.state());
    }

    @Override
    public List<SynthesizedDirective> getInjectable(String userId, int limit) {
        return jdbi.withExtension(PostgresDirectiveJdbiDao.class, dao -> dao.getInjectable(userId, limit));
    }

    @Override
    public List<SynthesizedDirective> getAll(String userId, int limit) {
        return jdbi.withExtension(PostgresDirectiveJdbiDao.class, dao -> dao.getAll(userId, limit));
    }

    @Override
    public SynthesizedDirective getById(String directiveId) {
        return jdbi.withExtension(PostgresDirectiveJdbiDao.class, dao -> dao.getById(directiveId));
    }

    @Override
    public List<SynthesizedDirective> getByState(DirectiveState state, int limit) {
        return jdbi.withExtension(PostgresDirectiveJdbiDao.class, dao -> dao.getByState(state.name(), limit));
    }

    @Override
    public boolean delete(String directiveId) {
        int rows = jdbi.withExtension(PostgresDirectiveJdbiDao.class, dao -> dao.deleteById(directiveId));
        log.debugf("[SelfEvolve] Deleted directive %s (found=%b)", directiveId, rows > 0);
        return rows > 0;
    }

    @Override
    public int purgeExpired(int retentionDays) {
        int purged = jdbi.withExtension(PostgresDirectiveJdbiDao.class, dao -> dao.purgeExpired(retentionDays));
        if (purged > 0) {
            log.infof("[SelfEvolve] Purged %d expired directives (retention=%d days)", purged, retentionDays);
        }
        return purged;
    }
}
