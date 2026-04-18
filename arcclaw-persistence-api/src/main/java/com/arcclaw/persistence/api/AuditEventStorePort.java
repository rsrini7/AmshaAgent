package com.arcclaw.persistence.api;

import java.util.List;
import java.util.Optional;

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
}
