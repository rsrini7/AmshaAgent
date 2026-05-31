package com.arcclaw.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.arcclaw.persistence.api.AuditEventDto;
import com.arcclaw.persistence.api.AuditEventStorePort;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "fs")
public class FsAuditEventStoreAdapter implements AuditEventStorePort {

    private static final Logger log = Logger.getLogger(FsAuditEventStoreAdapter.class);
    private static final String DOMAIN = "audit";

    @Inject
    FilesystemFileStore store;

    @Override
    public void save(AuditEventDto dto) {
        String sessionDir = dto.sessionId();
        String fileName = dto.id().toString() + ".json";
        log.debugf("Saving audit event %s for session %s", dto.id(), sessionDir);

        long seq = dto.sequence();
        SessionIndex index = loadIndex(sessionDir);
        if (seq > index.maxSequence) {
            index.maxSequence = seq;
            index.events.add(
                    new IndexEntry(dto.id().toString(), dto.idempotencyKey(), dto.eventType(), dto.timestamp()));
            saveIndex(sessionDir, index);
        }

        store.write(List.of(DOMAIN, sessionDir, fileName), dto);
    }

    @Override
    public Optional<String> findLatestHash(String sessionId) {
        SessionIndex index = loadIndex(sessionId);
        if (index.events.isEmpty()) {
            log.debugf("No audit events for session %s", sessionId);
            return Optional.empty();
        }
        String lastEventId = index.events.get(index.events.size() - 1).id;
        return store.readOptional(List.of(DOMAIN, sessionId, lastEventId + ".json"), AuditEventDto.class)
                .map(AuditEventDto::hmacHash);
    }

    @Override
    public List<AuditEventDto> findBySessionId(String sessionId) {
        SessionIndex index = loadIndex(sessionId);
        return index.events.stream()
                .map(e -> store.readOptional(List.of(DOMAIN, sessionId, e.id + ".json"), AuditEventDto.class))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public long countByIdempotencyKey(String key) {
        long count = 0;
        List<String> sessions = store.listDirs(List.of(DOMAIN));
        for (String session : sessions) {
            SessionIndex idx = loadIndex(session);
            count += idx.events.stream()
                    .filter(e -> key.equals(e.idempotencyKey))
                    .count();
        }
        return count;
    }

    @Override
    public long countEventsByType(String sessionId, String eventType) {
        SessionIndex index = loadIndex(sessionId);
        return index.events.stream().filter(e -> eventType.equals(e.eventType)).count();
    }

    @Override
    public long getMaxSequence(String sessionId) {
        return loadIndex(sessionId).maxSequence;
    }

    @Override
    public Optional<AuditEventDto> findById(UUID id) {
        if (id == null) return Optional.empty();
        List<String> sessions = store.listDirs(List.of(DOMAIN));
        for (String session : sessions) {
            Optional<AuditEventDto> found =
                    store.readOptional(List.of(DOMAIN, session, id.toString() + ".json"), AuditEventDto.class);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    @Override
    public List<AuditEventDto> findAll(
            String sessionId, String eventType, Instant from, Instant to, int offset, int limit) {
        List<AuditEventDto> results = new ArrayList<>();
        if (sessionId != null && !sessionId.isBlank()) {
            results.addAll(findBySessionId(sessionId));
        } else {
            List<String> sessions = store.listDirs(List.of(DOMAIN));
            for (String session : sessions) {
                results.addAll(findBySessionId(session));
            }
        }
        // Apply filters
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
        // Sort by timestamp descending
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

    private SessionIndex loadIndex(String sessionId) {
        return store.readOptional(List.of(DOMAIN, sessionId, "index.json"), SessionIndex.class)
                .orElse(new SessionIndex());
    }

    private void saveIndex(String sessionId, SessionIndex index) {
        store.write(List.of(DOMAIN, sessionId, "index.json"), index);
    }

    private record IndexEntry(String id, String idempotencyKey, String eventType, Instant timestamp) {}

    @SuppressWarnings("EffectivelyPrivate") // public fields needed for Jackson serialization
    private static class SessionIndex {
        public long maxSequence = 0;
        public List<IndexEntry> events = new ArrayList<>();
    }
}
