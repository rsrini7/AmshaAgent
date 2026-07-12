package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.persistence.api.SessionCatalogStore;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsSessionCatalogAdapter implements SessionCatalogStore {
    private static final List<String> ROOT = List.of("sessions", "catalog");

    @Inject
    FilesystemFileStore store;

    @Inject
    FsLockManager locks;

    @Override
    public void record(SessionCatalogEntry entry) {
        locked(entry.ownerKey(), entry.channelType(), entry.channelId(), () -> {
            var existing = bySessionId(entry.sessionId());
            int seq = entry.seq() > 0
                    ? entry.seq()
                    : existing.map(SessionCatalogEntry::seq).orElseGet(() -> next(entry));
            write(withSeq(entry, seq));
            return null;
        });
    }

    @Override
    public void touch(String sid, String ok, String ct, String ci, String role, String goal) {
        bySessionId(sid)
                .ifPresent(e -> write(new SessionCatalogEntry(
                        e.id(),
                        e.ownerKey(),
                        e.channelType(),
                        e.channelId(),
                        e.sessionId(),
                        blankOr(role, e.role()),
                        blankOr(goal, e.goalPreview()),
                        e.createdAt(),
                        Instant.now(),
                        e.seq(),
                        e.active(),
                        e.status())));
    }

    @Override
    public Optional<SessionCatalogEntry> resolveActive(String ok, String ct, String ci) {
        return listByScope(ok, ct, ci).stream()
                .filter(SessionCatalogEntry::active)
                .findFirst();
    }

    @Override
    public void activateNew(SessionCatalogEntry entry) {
        locked(entry.ownerKey(), entry.channelType(), entry.channelId(), () -> {
            listByScope(entry.ownerKey(), entry.channelType(), entry.channelId()).stream()
                    .filter(SessionCatalogEntry::active)
                    .forEach(e -> write(new SessionCatalogEntry(
                            e.id(),
                            e.ownerKey(),
                            e.channelType(),
                            e.channelId(),
                            e.sessionId(),
                            e.role(),
                            e.goalPreview(),
                            e.createdAt(),
                            e.lastUsedAt(),
                            e.seq(),
                            false,
                            "INACTIVE")));
            var existing = bySessionId(entry.sessionId());
            write(new SessionCatalogEntry(
                    entry.id(),
                    entry.ownerKey(),
                    entry.channelType(),
                    entry.channelId(),
                    entry.sessionId(),
                    entry.role(),
                    entry.goalPreview(),
                    entry.createdAt(),
                    entry.lastUsedAt(),
                    entry.seq() > 0
                            ? entry.seq()
                            : existing.map(SessionCatalogEntry::seq).orElseGet(() -> next(entry)),
                    true,
                    "ACTIVE"));
            return null;
        });
    }

    @Override
    public void setActive(String sid, boolean active) {
        if (active) bySessionId(sid).ifPresent(this::activateNew);
        else
            bySessionId(sid)
                    .ifPresent(e -> write(new SessionCatalogEntry(
                            e.id(),
                            e.ownerKey(),
                            e.channelType(),
                            e.channelId(),
                            e.sessionId(),
                            e.role(),
                            e.goalPreview(),
                            e.createdAt(),
                            e.lastUsedAt(),
                            e.seq(),
                            false,
                            "INACTIVE")));
    }

    @Override
    public List<SessionCatalogEntry> listByScope(String ok, String ct, String ci) {
        return all().stream()
                .filter(e -> e.ownerKey().equals(ok)
                        && e.channelType().equals(ct)
                        && e.channelId().equals(ci))
                .sorted(Comparator.comparingInt(SessionCatalogEntry::seq).reversed())
                .toList();
    }

    @Override
    public List<SessionCatalogEntry> listInactiveBefore(Instant cutoff) {
        return all().stream()
                .filter(e -> "INACTIVE".equals(e.status()) && e.lastUsedAt().isBefore(cutoff))
                .toList();
    }

    @Override
    public Optional<SessionCatalogEntry> latestByScope(String ok, String ct, String ci) {
        return listByScope(ok, ct, ci).stream().max(Comparator.comparing(SessionCatalogEntry::lastUsedAt));
    }

    @Override
    public Optional<SessionCatalogEntry> bySessionId(String sid) {
        return store.readOptional(path(sid), SessionCatalogEntry.class);
    }

    @Override
    public Optional<SessionCatalogEntry> bySequence(String ok, String ct, String ci, int seq) {
        return listByScope(ok, ct, ci).stream().filter(e -> e.seq() == seq).findFirst();
    }

    @Override
    public void archive(String sid) {
        bySessionId(sid)
                .ifPresent(e -> write(new SessionCatalogEntry(
                        e.id(),
                        e.ownerKey(),
                        e.channelType(),
                        e.channelId(),
                        e.sessionId(),
                        e.role(),
                        e.goalPreview(),
                        e.createdAt(),
                        e.lastUsedAt(),
                        e.seq(),
                        false,
                        "ARCHIVED")));
    }

    private List<SessionCatalogEntry> all() {
        return store.list(ROOT, ".json").stream()
                .map(n -> store.readOptional(List.of("sessions", "catalog", n), SessionCatalogEntry.class))
                .flatMap(Optional::stream)
                .toList();
    }

    private int next(SessionCatalogEntry e) {
        return listByScope(e.ownerKey(), e.channelType(), e.channelId()).stream()
                        .mapToInt(SessionCatalogEntry::seq)
                        .max()
                        .orElse(0)
                + 1;
    }

    private void write(SessionCatalogEntry e) {
        store.write(path(e.sessionId()), e);
    }

    private List<String> path(String sid) {
        return List.of("sessions", "catalog", sid + ".json");
    }

    private SessionCatalogEntry withSeq(SessionCatalogEntry e, int seq) {
        return new SessionCatalogEntry(
                e.id(),
                e.ownerKey(),
                e.channelType(),
                e.channelId(),
                e.sessionId(),
                e.role(),
                e.goalPreview(),
                e.createdAt(),
                e.lastUsedAt(),
                seq,
                e.active(),
                e.status());
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private <T> T locked(String ok, String ct, String ci, Supplier<T> op) {
        String key = "session-catalog-" + UUID.nameUUIDFromBytes((ok + "/" + ct + "/" + ci).getBytes());
        if (!locks.tryLock(key, 10)) throw new IllegalStateException("Session catalog scope is busy");
        try {
            return op.get();
        } finally {
            locks.unlock(key);
        }
    }
}
