package com.amsha.core.test;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.persistence.api.SessionCatalogStore;

@Alternative
@Priority(1)
@ApplicationScoped
public class MockSessionCatalogStore implements SessionCatalogStore {

    private final Map<String, SessionCatalogEntry> store = new ConcurrentHashMap<>();

    @Override
    public void record(SessionCatalogEntry entry) {
        int seq = entry.seq() > 0 ? entry.seq() : nextSeq(entry);
        store.put(entry.sessionId(), withSeq(entry, seq));
    }

    @Override
    public void touch(
            String sessionId, String ownerKey, String channelType, String channelId, String role, String goalPreview) {
        SessionCatalogEntry e = store.get(sessionId);
        if (e != null) {
            store.put(
                    sessionId,
                    new SessionCatalogEntry(
                            e.id(),
                            e.ownerKey(),
                            e.channelType(),
                            e.channelId(),
                            e.sessionId(),
                            role != null && !role.isBlank() ? role : e.role(),
                            goalPreview != null && !goalPreview.isBlank() ? goalPreview : e.goalPreview(),
                            e.createdAt(),
                            Instant.now(),
                            e.seq(),
                            e.active(),
                            e.status()));
        }
    }

    @Override
    public Optional<SessionCatalogEntry> resolveActive(String ownerKey, String channelType, String channelId) {
        return store.values().stream()
                .filter(e -> e.ownerKey().equals(ownerKey)
                        && e.channelType().equals(channelType)
                        && e.channelId().equals(channelId)
                        && e.active())
                .findFirst();
    }

    @Override
    public void activateNew(SessionCatalogEntry entry) {
        store.values().stream()
                .filter(e -> e.ownerKey().equals(entry.ownerKey())
                        && e.channelType().equals(entry.channelType())
                        && e.channelId().equals(entry.channelId())
                        && e.active())
                .forEach(e -> store.put(
                        e.sessionId(),
                        new SessionCatalogEntry(
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
        int seq = entry.seq() > 0 ? entry.seq() : nextSeq(entry);
        store.put(
                entry.sessionId(),
                new SessionCatalogEntry(
                        entry.id(),
                        entry.ownerKey(),
                        entry.channelType(),
                        entry.channelId(),
                        entry.sessionId(),
                        entry.role(),
                        entry.goalPreview(),
                        entry.createdAt(),
                        entry.lastUsedAt(),
                        seq,
                        true,
                        "ACTIVE"));
    }

    @Override
    public void setActive(String sessionId, boolean active) {
        if (active) {
            SessionCatalogEntry e = store.get(sessionId);
            if (e != null) {
                activateNew(e);
            }
        } else {
            SessionCatalogEntry e = store.get(sessionId);
            if (e != null) {
                store.put(
                        sessionId,
                        new SessionCatalogEntry(
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
                                "INACTIVE"));
            }
        }
    }

    @Override
    public List<SessionCatalogEntry> listByScope(String ownerKey, String channelType, String channelId) {
        return store.values().stream()
                .filter(e -> e.ownerKey().equals(ownerKey)
                        && e.channelType().equals(channelType)
                        && e.channelId().equals(channelId))
                .sorted(Comparator.comparingInt(SessionCatalogEntry::seq).reversed())
                .toList();
    }

    @Override
    public List<SessionCatalogEntry> listInactiveBefore(Instant cutoff) {
        return store.values().stream()
                .filter(e -> "INACTIVE".equals(e.status()) && e.lastUsedAt().isBefore(cutoff))
                .toList();
    }

    @Override
    public Optional<SessionCatalogEntry> latestByScope(String ownerKey, String channelType, String channelId) {
        return listByScope(ownerKey, channelType, channelId).stream()
                .max(Comparator.comparing(SessionCatalogEntry::lastUsedAt));
    }

    @Override
    public Optional<SessionCatalogEntry> bySessionId(String sessionId) {
        return Optional.ofNullable(store.get(sessionId));
    }

    @Override
    public Optional<SessionCatalogEntry> bySequence(String ownerKey, String channelType, String channelId, int seq) {
        return listByScope(ownerKey, channelType, channelId).stream()
                .filter(e -> e.seq() == seq)
                .findFirst();
    }

    @Override
    public void archive(String sessionId) {
        SessionCatalogEntry e = store.get(sessionId);
        if (e != null) {
            store.put(
                    sessionId,
                    new SessionCatalogEntry(
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
                            "ARCHIVED"));
        }
    }

    private int nextSeq(SessionCatalogEntry e) {
        return listByScope(e.ownerKey(), e.channelType(), e.channelId()).stream()
                        .mapToInt(SessionCatalogEntry::seq)
                        .max()
                        .orElse(0)
                + 1;
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
}
