package com.amsha.persistence.postgres.jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.AuditEventDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(AuditEventDto.class)
public interface AuditJdbiDao {

    @SqlUpdate(
            "INSERT INTO audit_event (id, sessionId, userId, agentRole, goalHash, outcome, eventType, eventDetail, inputTokens, outputTokens, usdCost, timestamp, iterationsUsed, app_version, idempotency_key, sequence, hmacHash, prevHash) VALUES (:id, :sessionId, :userId, :agentRole, :goalHash, :outcome, :eventType, :eventDetail, :inputTokens, :outputTokens, :usdCost, :timestamp, :iterationsUsed, :appVersion, :idempotencyKey, :sequence, :hmacHash, :prevHash)")
    void save(@BindMethods AuditEventDto dto);

    @SqlQuery(
            "SELECT id, sessionId, userId, agentRole, goalHash, outcome, eventType, eventDetail, inputTokens, outputTokens, usdCost, timestamp, iterationsUsed, app_version AS \"appVersion\", idempotency_key AS \"idempotencyKey\", sequence, hmacHash, prevHash FROM audit_event WHERE sessionId = :sessionId ORDER BY sequence ASC")
    List<AuditEventDto> findBySessionId(@Bind("sessionId") String sessionId);

    @SqlQuery(
            "SELECT hmacHash FROM audit_event WHERE sessionId = :sessionId ORDER BY sequence DESC, timestamp DESC LIMIT 1")
    Optional<String> findLatestHash(@Bind("sessionId") String sessionId);

    @SqlQuery("SELECT COUNT(*) FROM audit_event WHERE idempotency_key = :key")
    long countByIdempotencyKey(@Bind("key") String key);

    @SqlQuery("SELECT COUNT(*) FROM audit_event WHERE sessionId = :sessionId AND eventType = :eventType")
    long countEventsByType(@Bind("sessionId") String sessionId, @Bind("eventType") String eventType);

    @SqlQuery("SELECT COALESCE(MAX(sequence), 0) FROM audit_event WHERE sessionId = :sessionId")
    long getMaxSequence(@Bind("sessionId") String sessionId);

    @SqlQuery(
            "SELECT id, sessionId, userId, agentRole, goalHash, outcome, eventType, eventDetail, inputTokens, outputTokens, usdCost, timestamp, iterationsUsed, app_version AS \"appVersion\", idempotency_key AS \"idempotencyKey\", sequence, hmacHash, prevHash FROM audit_event WHERE id = :id")
    Optional<AuditEventDto> findById(@Bind("id") UUID id);

    @SqlQuery(
            "SELECT id, sessionId, userId, agentRole, goalHash, outcome, eventType, eventDetail, inputTokens, outputTokens, usdCost, timestamp, iterationsUsed, app_version AS \"appVersion\", idempotency_key AS \"idempotencyKey\", sequence, hmacHash, prevHash FROM audit_event WHERE (:sessionId::text IS NULL OR sessionId = :sessionId) AND (:eventType::text IS NULL OR eventType = :eventType) AND (:from::timestamptz IS NULL OR timestamp >= :from) AND (:to::timestamptz IS NULL OR timestamp <= :to) ORDER BY timestamp DESC LIMIT :limit::INTEGER OFFSET :offset::INTEGER")
    List<AuditEventDto> findFiltered(
            @Bind("sessionId") String sessionId,
            @Bind("eventType") String eventType,
            @Bind("from") Instant from,
            @Bind("to") Instant to,
            @Bind("offset") int offset,
            @Bind("limit") int limit);

    @SqlQuery(
            "SELECT COUNT(*) FROM audit_event WHERE (:sessionId::text IS NULL OR sessionId = :sessionId) AND (:eventType::text IS NULL OR eventType = :eventType) AND (:from::timestamptz IS NULL OR timestamp >= :from) AND (:to::timestamptz IS NULL OR timestamp <= :to)")
    long countFiltered(
            @Bind("sessionId") String sessionId,
            @Bind("eventType") String eventType,
            @Bind("from") Instant from,
            @Bind("to") Instant to);
}
