package com.arcclaw.persistence.fs;

import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.persistence.api.AuditEventDto;
import com.arcclaw.persistence.api.AuditEventStorePort;
import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.arc.Unremovable;
import org.jboss.logging.Logger;

@ApplicationScoped
@Unremovable
public class FsAuditEventStoreAdapter implements AuditEventStorePort {

    private static final Logger log = Logger.getLogger(FsAuditEventStoreAdapter.class);
    private static final String DOMAIN = "audit";

    @Inject
    FilesystemFileStore store;

    @Override
    public void save(AuditEventDto dto) {
        String sessionDir = dto.sessionId();
        String fileName = dto.id().toString() + ".json";

        long seq = dto.sequence();
        SessionIndex index = loadIndex(sessionDir);
        if (seq > index.maxSequence) {
            index.maxSequence = seq;
            index.events.add(new IndexEntry(dto.id().toString(), dto.idempotencyKey(), dto.eventType(), dto.timestamp()));
            saveIndex(sessionDir, index);
        }

        store.write(List.of(DOMAIN, sessionDir, fileName), dto);
    }

    @Override
    public Optional<String> findLatestHash(String sessionId) {
        SessionIndex index = loadIndex(sessionId);
        if (index.events.isEmpty()) return Optional.empty();
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
            count += idx.events.stream().filter(e -> key.equals(e.idempotencyKey)).count();
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

    private SessionIndex loadIndex(String sessionId) {
        return store.readOptional(List.of(DOMAIN, sessionId, "index.json"), SessionIndex.class)
            .orElse(new SessionIndex());
    }

    private void saveIndex(String sessionId, SessionIndex index) {
        store.write(List.of(DOMAIN, sessionId, "index.json"), index);
    }

    @SuppressWarnings("unused")
    private record IndexEntry(String id, String idempotencyKey, String eventType, java.time.Instant timestamp) {}

    @SuppressWarnings("unused")
    private static class SessionIndex {
        public long maxSequence = 0;
        public List<IndexEntry> events = new java.util.ArrayList<>();
    }
}
