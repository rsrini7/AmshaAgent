package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.MemoryRecordType;
import com.amsha.selfevolve.store.ExtractedMemoryStorePort;

import org.jboss.logging.Logger;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * Filesystem implementation of extracted memory store.
 * Stores memories as JSON wrapper per session in {dataDir}/extracted/{sessionId}.json.
 */
@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsExtractedMemoryStore implements ExtractedMemoryStorePort {

    private static final Logger log = Logger.getLogger(FsExtractedMemoryStore.class);
    private static final String DOMAIN = "extracted";

    record MemoryBatch(List<ExtractedMemory> memories) {}

    @Inject
    FilesystemFileStore store;

    @Override
    public int insertBatch(List<ExtractedMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return 0;
        }
        String sessionId = memories.getFirst().sessionId();
        List<ExtractedMemory> existing = loadMemories(sessionId);
        int before = existing.size();
        var existingHashes = new HashSet<String>();
        for (ExtractedMemory m : existing) {
            existingHashes.add(m.contentHash());
        }
        for (ExtractedMemory m : memories) {
            if (!existingHashes.contains(m.contentHash())) {
                existing.add(m);
                existingHashes.add(m.contentHash());
            }
        }
        int persisted = existing.size() - before;
        store.write(List.of(DOMAIN, sessionId + ".json"), new MemoryBatch(existing));
        log.debugf(
                "[SelfEvolve-FS] Persisted %d/%d extracted memories for session %s",
                persisted, memories.size(), sessionId);
        return persisted;
    }

    @Override
    public List<ExtractedMemory> getBySession(String sessionId, int limit) {
        List<ExtractedMemory> memories = loadMemories(sessionId);
        memories.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        if (limit > 0 && memories.size() > limit) {
            return memories.subList(0, limit);
        }
        return memories;
    }

    @Override
    public List<ExtractedMemory> getByUser(String userId, int limit) {
        List<ExtractedMemory> all = new ArrayList<>();
        for (String f : store.list(List.of(DOMAIN), ".json")) {
            for (ExtractedMemory m : loadMemoriesFromFile(f)) {
                if (userId.equals(m.userId())) {
                    all.add(m);
                }
            }
        }
        all.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        if (limit > 0 && all.size() > limit) {
            return all.subList(0, limit);
        }
        return all;
    }

    @Override
    public List<ExtractedMemory> getByType(MemoryRecordType recordType, int limit) {
        List<ExtractedMemory> all = new ArrayList<>();
        for (String f : store.list(List.of(DOMAIN), ".json")) {
            for (ExtractedMemory m : loadMemoriesFromFile(f)) {
                if (recordType == m.recordType()) {
                    all.add(m);
                }
            }
        }
        all.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        if (limit > 0 && all.size() > limit) {
            return all.subList(0, limit);
        }
        return all;
    }

    @Override
    public List<String> listUserIds(int limit) {
        var userIds = new HashSet<String>();
        for (String f : store.list(List.of(DOMAIN), ".json")) {
            for (ExtractedMemory m : loadMemoriesFromFile(f)) {
                if (m.userId() != null && !m.userId().isBlank()) {
                    userIds.add(m.userId());
                }
            }
        }
        List<String> users = new ArrayList<>(userIds);
        users.sort(String::compareTo);
        if (limit > 0 && users.size() > limit) {
            return users.subList(0, limit);
        }
        return users;
    }

    @Override
    public boolean existsByHash(String sessionId, String contentHash) {
        return loadMemories(sessionId).stream().anyMatch(m -> contentHash.equals(m.contentHash()));
    }

    @Override
    public int purgeExpired(int retentionDays) {
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86400L);
        int purged = 0;
        for (String f : store.list(List.of(DOMAIN), ".json")) {
            List<ExtractedMemory> memories = loadMemoriesFromFile(f);
            int before = memories.size();
            memories.removeIf(m -> m.createdAt().isBefore(cutoff));
            if (memories.size() < before) {
                store.write(List.of(DOMAIN, f), new MemoryBatch(memories));
                purged += before - memories.size();
            }
        }
        return purged;
    }

    private List<ExtractedMemory> loadMemories(String sessionId) {
        return loadMemoriesFromFile(sessionId + ".json");
    }

    private List<ExtractedMemory> loadMemoriesFromFile(String filename) {
        Optional<MemoryBatch> batch = store.readOptional(List.of(DOMAIN, filename), MemoryBatch.class);
        return batch.map(b -> new ArrayList<>(b.memories())).orElseGet(ArrayList::new);
    }
}
