package com.arcclaw.core.test;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    @Override
    public Optional<AuditEventDto> findById(UUID id) {
        if (id == null) return Optional.empty();
        return store.values().stream()
                .flatMap(List::stream)
                .filter(e -> id.equals(e.id()))
                .findFirst();
    }

    @Override
    public List<AuditEventDto> findAll(
            String sessionId, String eventType, Instant from, Instant to, int offset, int limit) {
        List<AuditEventDto> results = new ArrayList<>();
        if (sessionId != null && !sessionId.isBlank()) {
            results.addAll(store.getOrDefault(sessionId, Collections.emptyList()));
        } else {
            store.values().forEach(results::addAll);
        }
        if (eventType != null && !eventType.isBlank()) {
            results = results.stream()
                    .filter(e -> eventType.equals(e.eventType()))
                    .toList();
        }
        if (from != null) {
            results = results.stream()
                    .filter(e -> e.timestamp() != null && !e.timestamp().isBefore(from))
                    .toList();
        }
        if (to != null) {
            results = results.stream()
                    .filter(e -> e.timestamp() != null && !e.timestamp().isAfter(to))
                    .toList();
        }
        results = results.stream()
                .sorted((a, b) -> {
                    if (a.timestamp() == null && b.timestamp() == null) return 0;
                    if (a.timestamp() == null) return 1;
                    if (b.timestamp() == null) return -1;
                    return b.timestamp().compareTo(a.timestamp());
                })
                .toList();
        int end = Math.min(offset + limit, results.size());
        if (offset >= results.size()) return List.of();
        return results.subList(offset, end);
    }

    @Override
    public long countAll(String sessionId, String eventType, Instant from, Instant to) {
        return findAll(sessionId, eventType, from, to, 0, Integer.MAX_VALUE).size();
    }

    /** Test helper: replace the DTO at a given index for a session. */
    public void replaceDto(String sessionId, int index, AuditEventDto dto) {
        List<AuditEventDto> dtos = store.get(sessionId);
        if (dtos != null && index >= 0 && index < dtos.size()) {
            dtos.set(index, dto);
        }
    }
}
