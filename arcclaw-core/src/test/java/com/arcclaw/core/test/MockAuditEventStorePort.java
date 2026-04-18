package com.arcclaw.core.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import com.arcclaw.persistence.api.AuditEventDto;
import com.arcclaw.persistence.api.AuditEventStorePort;

@Alternative
@Priority(1)
@ApplicationScoped
public class MockAuditEventStorePort implements AuditEventStorePort {

    private final Map<String, List<AuditEventDto>> store = new ConcurrentHashMap<>();

    @Override 
    public void save(AuditEventDto dto) {
        store.computeIfAbsent(dto.sessionId(), k -> new ArrayList<>()).add(dto);
    }

    @Override 
    public Optional<String> findLatestHash(String sessionId) {
        List<AuditEventDto> events = store.getOrDefault(sessionId, Collections.emptyList());
        if (events.isEmpty()) return Optional.empty();
        return Optional.of(events.get(events.size() - 1).hmacHash());
    }

    @Override 
    public List<AuditEventDto> findBySessionId(String sessionId) {
        return new ArrayList<>(store.getOrDefault(sessionId, Collections.emptyList()));
    }

    @Override 
    public long countByIdempotencyKey(String key) {
        return store.values().stream()
                .flatMap(List::stream)
                .filter(e -> key.equals(e.idempotencyKey()))
                .count();
    }

    @Override 
    public long countEventsByType(String sessionId, String eventType) {
        return store.getOrDefault(sessionId, Collections.emptyList()).stream()
                .filter(e -> eventType.equals(e.eventType()))
                .count();
    }

    @Override 
    public long getMaxSequence(String sessionId) {
        return store.getOrDefault(sessionId, Collections.emptyList()).stream()
                .mapToLong(AuditEventDto::sequence)
                .max()
                .orElse(0L);
    }
}
