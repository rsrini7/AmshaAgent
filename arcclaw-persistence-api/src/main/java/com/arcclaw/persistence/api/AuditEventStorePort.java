package com.arcclaw.persistence.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for persisting and querying audit events.
 * §1.2: Core-persistence boundary.
 */
public interface AuditEventStorePort {
    void save(AuditEventDto dto);

    Optional<String> findLatestHash(String sessionId);

    List<AuditEventDto> findBySessionId(String sessionId);

    long countByIdempotencyKey(String key);

    long countEventsByType(String sessionId, String eventType);

    long getMaxSequence(String sessionId);

    /**
     * Returns a single audit event by ID.
     */
    Optional<AuditEventDto> findById(UUID id);

    /**
     * Returns audit events with pagination and optional filters.
     * @param sessionId if non-null, filter by session
     * @param eventType if non-null, filter by event type
     * @param from      if non-null, filter events after this timestamp
     * @param to        if non-null, filter events before this timestamp
     * @param offset    pagination offset (0-based)
     * @param limit     max results to return
     */
    List<AuditEventDto> findAll(String sessionId, String eventType, Instant from, Instant to, int offset, int limit);

    /**
     * Returns total count of audit events matching the given filters.
     */
    long countAll(String sessionId, String eventType, Instant from, Instant to);
}
