package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import com.amsha.persistence.api.AuditChainFinalizer;
import com.amsha.persistence.api.AuditEventDto;
import com.amsha.persistence.api.AuditEventStorePort;
import com.amsha.persistence.api.PersistenceBackend;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
public class FsAuditEventStoreAdapter implements AuditEventStorePort {

    private static final Logger log = Logger.getLogger(FsAuditEventStoreAdapter.class);
    private static final String DOMAIN = "audit";
    private final ConcurrentHashMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

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
    public AuditEventDto appendChained(AuditEventDto seed, AuditChainFinalizer finalizer) {
        return withSessionLock(seed.sessionId(), () -> {
            SessionIndex index = rebuildIndex(seed.sessionId());
            if (seed.idempotencyKey() != null) {
                for (IndexEntry entry : index.events) {
                    if (seed.idempotencyKey().equals(entry.idempotencyKey)) {
                        Optional<AuditEventDto> existing = store.readOptional(
                                List.of(DOMAIN, seed.sessionId(), entry.id + ".json"), AuditEventDto.class);
                        if (existing.isPresent()) {
                            saveIndex(seed.sessionId(), index);
                            return existing.get();
                        }
                    }
                }
            }
            String previousHash = index.events.isEmpty()
                    ? null
                    : store.readOptional(
                                    List.of(DOMAIN, seed.sessionId(), index.events.getLast().id + ".json"),
                                    AuditEventDto.class)
                            .map(AuditEventDto::hmacHash)
                            .orElse(null);
            AuditEventDto finalized =
                    AuditEventStorePort.finalizeAndValidate(seed, index.maxSequence + 1, previousHash, finalizer);
            store.write(List.of(DOMAIN, seed.sessionId(), finalized.id() + ".json"), finalized);
            index.maxSequence = finalized.sequence();
            index.events.add(new IndexEntry(
                    finalized.id().toString(),
                    finalized.idempotencyKey(),
                    finalized.eventType(),
                    finalized.timestamp()));
            saveIndex(seed.sessionId(), index);
            return finalized;
        });
    }

    private <T> T withSessionLock(String sessionId, Supplier<T> action) {
        ReentrantLock jvmLock = sessionLocks.computeIfAbsent(sessionId, _ -> new ReentrantLock());
        jvmLock.lock();
        try {
            Path lockPath = store.resolve(List.of(DOMAIN, sessionId, ".append.lock"));
            Files.createDirectories(lockPath.getParent());
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock fileLock = channel.lock()) {
                if (!fileLock.isValid()) {
                    throw new IllegalStateException("Audit session lock is not valid");
                }
                return action.get();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to lock audit session", e);
        } finally {
            jvmLock.unlock();
        }
    }

    private SessionIndex rebuildIndex(String sessionId) {
        SessionIndex index = new SessionIndex();
        List<AuditEventDto> events = store.list(List.of(DOMAIN, sessionId), ".json").stream()
                .filter(name -> !"index.json".equals(name))
                .map(name -> store.readOptional(List.of(DOMAIN, sessionId, name), AuditEventDto.class))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingLong(AuditEventDto::sequence))
                .toList();
        for (AuditEventDto event : events) {
            index.maxSequence = Math.max(index.maxSequence, event.sequence());
            index.events.add(new IndexEntry(
                    event.id().toString(), event.idempotencyKey(), event.eventType(), event.timestamp()));
        }
        return index;
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
