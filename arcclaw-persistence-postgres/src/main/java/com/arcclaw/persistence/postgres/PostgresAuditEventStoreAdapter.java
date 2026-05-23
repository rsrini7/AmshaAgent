package com.arcclaw.persistence.postgres;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.persistence.api.AuditEventDto;
import com.arcclaw.persistence.api.AuditEventStorePort;
import com.arcclaw.persistence.postgres.jdbi.AuditJdbiDao;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

/**
 * PostgreSQL implementation of the AuditEventStorePort using JDBI.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresAuditEventStoreAdapter implements AuditEventStorePort {

    private static final Logger log = Logger.getLogger(PostgresAuditEventStoreAdapter.class);

    private final Jdbi jdbi;

    @Inject
    public PostgresAuditEventStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresAuditEventStoreAdapter() {
        this(null);
    }

    @Override
    public void save(AuditEventDto dto) {
        log.tracef("save event session=%s type=%s", dto.sessionId(), dto.eventType());
        jdbi.useExtension(AuditJdbiDao.class, dao -> dao.save(dto));
    }

    @Override
    public Optional<String> findLatestHash(String sessionId) {
        return jdbi.withExtension(AuditJdbiDao.class, dao -> dao.findLatestHash(sessionId));
    }

    @Override
    public List<AuditEventDto> findBySessionId(String sessionId) {
        return jdbi.withExtension(AuditJdbiDao.class, dao -> dao.findBySessionId(sessionId));
    }

    @Override
    public long countByIdempotencyKey(String key) {
        return jdbi.withExtension(AuditJdbiDao.class, dao -> dao.countByIdempotencyKey(key));
    }

    @Override
    public long countEventsByType(String sessionId, String eventType) {
        return jdbi.withExtension(AuditJdbiDao.class, dao -> dao.countEventsByType(sessionId, eventType));
    }

    @Override
    public long getMaxSequence(String sessionId) {
        return jdbi.withExtension(AuditJdbiDao.class, dao -> dao.getMaxSequence(sessionId));
    }

    @Override
    public Optional<AuditEventDto> findById(UUID id) {
        if (id == null) return Optional.empty();
        return jdbi.withExtension(AuditJdbiDao.class, dao -> dao.findById(id));
    }

    @Override
    public List<AuditEventDto> findAll(String sessionId, String eventType, Instant from, Instant to, int offset, int limit) {
        return jdbi.withExtension(AuditJdbiDao.class,
            dao -> dao.findFiltered(sessionId, eventType, from, to, offset, limit));
    }

    @Override
    public long countAll(String sessionId, String eventType, Instant from, Instant to) {
        return jdbi.withExtension(AuditJdbiDao.class,
            dao -> dao.countFiltered(sessionId, eventType, from, to));
    }
}
