package com.amsha.selfevolve.store.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.selfevolve.api.EvolutionEvent;
import com.amsha.selfevolve.store.EvolutionEventStorePort;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * PostgreSQL implementation of evolution event store.
 * Uses JDBI with ON CONFLICT DO NOTHING for DB-enforced idempotency.
 */
@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
public class PostgresEvolutionEventStore implements EvolutionEventStorePort {

    private static final Logger log = Logger.getLogger(PostgresEvolutionEventStore.class);

    /** Injected Jdbi carries the shared PostgreSQL argument and column mappers. */
    @Inject
    Jdbi jdbi;

    @Override
    public int insertBatch(List<EvolutionEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        int[] results = jdbi.withExtension(PostgresEvolutionEventJdbiDao.class, dao -> dao.insertBatch(events));
        int persisted = 0;
        for (int r : results) {
            if (r > 0) persisted++;
        }
        log.debugf("[SelfEvolve] Persisted %d/%d Postgres events", persisted, events.size());
        return persisted;
    }

    @Override
    public List<EvolutionEvent> getBySession(String sessionId, int limit) {
        return jdbi.withExtension(PostgresEvolutionEventJdbiDao.class, dao -> dao.getBySession(sessionId, limit));
    }

    @Override
    public List<EvolutionEvent> getByUser(String userId, int limit) {
        return jdbi.withExtension(PostgresEvolutionEventJdbiDao.class, dao -> dao.getByUser(userId, limit));
    }

    @Override
    public int purgeExpired(int retentionDays) {
        return jdbi.withExtension(PostgresEvolutionEventJdbiDao.class, dao -> dao.purgeExpired(retentionDays));
    }
}
