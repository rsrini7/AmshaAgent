package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.util.TemporalUtils;
import com.amsha.persistence.api.AuditEventDto;
import com.amsha.persistence.api.AuditEventStorePort;
import com.amsha.persistence.sqlite.jdbi.AuditJdbiDao;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteAuditEventStoreAdapter implements AuditEventStorePort {

    private static final Logger log = Logger.getLogger(SqliteAuditEventStoreAdapter.class);

    private final Jdbi jdbi;

    @Inject
    public SqliteAuditEventStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SqliteAuditEventStoreAdapter() {
        this(null);
    }

    @Override
    public void save(AuditEventDto dto) {
        log.tracef("save event session=%s type=%s", dto.sessionId(), dto.eventType());
        jdbi.useHandle(handle -> handle.createUpdate(
                        "INSERT INTO audit_event (id, sessionId, userId, agentRole, goalHash, outcome, eventType, "
                                + "eventDetail, inputTokens, outputTokens, usdCost, timestamp, iterationsUsed, app_version, "
                                + "idempotency_key, sequence, hmacHash, prevHash) "
                                + "VALUES (:id, :sessionId, :userId, :agentRole, :goalHash, :outcome, :eventType, "
                                + ":eventDetail, :inputTokens, :outputTokens, :usdCost, :timestamp, :iterationsUsed, "
                                + ":appVersion, :idempotencyKey, :sequence, :hmacHash, :prevHash)")
                .bind("id", dto.id())
                .bind("sessionId", dto.sessionId())
                .bind("userId", dto.userId())
                .bind("agentRole", dto.agentRole())
                .bind("goalHash", dto.goalHash())
                .bind("outcome", dto.outcome())
                .bind("eventType", dto.eventType())
                .bind("eventDetail", dto.eventDetail())
                .bind("inputTokens", dto.inputTokens())
                .bind("outputTokens", dto.outputTokens())
                .bind("usdCost", dto.usdCost())
                .bind("timestamp", dto.timestamp())
                .bind("iterationsUsed", dto.iterationsUsed())
                .bind("appVersion", dto.appVersion())
                .bind("idempotencyKey", dto.idempotencyKey())
                .bind("sequence", dto.sequence())
                .bind("hmacHash", dto.hmacHash())
                .bind("prevHash", dto.prevHash())
                .execute());
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
    public List<AuditEventDto> findAll(
            String sessionId, String eventType, Instant from, Instant to, int offset, int limit) {
        return jdbi.withExtension(
                AuditJdbiDao.class,
                dao -> dao.findFiltered(
                        sessionId,
                        eventType,
                        from != null ? TemporalUtils.formatIsoLex(from) : null,
                        to != null ? TemporalUtils.formatIsoLex(to) : null,
                        offset,
                        limit));
    }

    @Override
    public long countAll(String sessionId, String eventType, Instant from, Instant to) {
        return jdbi.withExtension(
                AuditJdbiDao.class,
                dao -> dao.countFiltered(
                        sessionId,
                        eventType,
                        from != null ? TemporalUtils.formatIsoLex(from) : null,
                        to != null ? TemporalUtils.formatIsoLex(to) : null));
    }
}
