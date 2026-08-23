package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalMemoryStorePort;
import com.amsha.persistence.api.ArchivalSearchQuery;
import com.amsha.persistence.api.TieredBackend;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * Filesystem implementation of Archival Memory store.
 * Stores chunks as JSON per user in {dataDir}/archival/{userId}.json.
 * Search uses case-insensitive substring matching (no Lucene vector — FS backend is BM25-free for tiered memory).
 */
@ApplicationScoped
@Unremovable
@TieredBackend("fs")
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsArchivalMemoryStore implements ArchivalMemoryStorePort {

    private static final Logger log = Logger.getLogger(FsArchivalMemoryStore.class);
    private static final String DOMAIN = "archival";

    record ChunkList(List<ArchivalChunkDto> chunks) {}

    @Inject
    FilesystemFileStore store;

    @Override
    public void upsert(ArchivalChunkDto chunk) {
        List<ArchivalChunkDto> chunks = loadChunks(chunk.userId());
        // Remove existing chunk with same ID
        chunks.removeIf(c -> c.chunkId().equals(chunk.chunkId()));
        chunks.add(chunk);
        store.write(List.of(DOMAIN, chunk.userId() + ".json"), new ChunkList(chunks));
        log.debugf("[FsArchival] Upserted chunk %s for user %s", chunk.chunkId(), chunk.userId());
    }

    @Override
    public List<ArchivalChunkDto> search(ArchivalSearchQuery query) {
        String q = query.query() != null ? query.query().toLowerCase(Locale.ROOT) : "";
        boolean chunkTypesEmpty =
                query.chunkTypes() == null || query.chunkTypes().isEmpty();

        List<ArchivalChunkDto> chunks = loadChunks(query.userId());
        List<ArchivalChunkDto> results = new ArrayList<>();

        for (ArchivalChunkDto c : chunks) {
            // Chunk type filter
            if (!chunkTypesEmpty && c.chunkType() != null && !query.chunkTypes().contains(c.chunkType())) {
                continue;
            }
            // Text search
            if (!q.isEmpty()
                    && c.content() != null
                    && !c.content().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            results.add(c);
        }

        // Sort by confidence descending, then by createdAt descending
        results.sort((a, b) -> {
            int cmp = Double.compare(b.confidence(), a.confidence());
            if (cmp != 0) return cmp;
            if (a.createdAt() != null && b.createdAt() != null) {
                return b.createdAt().compareTo(a.createdAt());
            }
            return 0;
        });

        if (query.limit() > 0 && results.size() > query.limit()) {
            return results.subList(0, query.limit());
        }
        return results;
    }

    @Override
    public Optional<ArchivalChunkDto> getById(String chunkId) {
        // Search all users for the chunk
        for (String f : store.list(List.of(DOMAIN), ".json")) {
            for (ArchivalChunkDto c : loadChunksFromFile(f)) {
                if (c.chunkId().equals(chunkId)) {
                    return Optional.of(c);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void incrementAccessCount(String chunkId) {
        for (String f : store.list(List.of(DOMAIN), ".json")) {
            List<ArchivalChunkDto> chunks = loadChunksFromFile(f);
            boolean found = false;
            for (int i = 0; i < chunks.size(); i++) {
                ArchivalChunkDto c = chunks.get(i);
                if (c.chunkId().equals(chunkId)) {
                    chunks.set(
                            i,
                            new ArchivalChunkDto(
                                    c.chunkId(),
                                    c.userId(),
                                    c.content(),
                                    c.chunkType(),
                                    c.sourceSession(),
                                    c.sourceMessage(),
                                    c.tags(),
                                    c.confidence(),
                                    c.accessCount() + 1,
                                    c.createdAt(),
                                    Instant.now()));
                    found = true;
                    break;
                }
            }
            if (found) {
                // Determine userId from the chunk to write back
                for (ArchivalChunkDto c : chunks) {
                    if (c.chunkId().equals(chunkId)
                            || chunks.stream().anyMatch(x -> x.chunkId().equals(chunkId))) {
                        store.write(List.of(DOMAIN, c.userId() + ".json"), new ChunkList(chunks));
                        break;
                    }
                }
                break;
            }
        }
    }

    @Override
    public void incrementAccessCounts(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return;
        for (String id : chunkIds) {
            incrementAccessCount(id);
        }
    }

    @Override
    public void delete(String chunkId) {
        for (String f : store.list(List.of(DOMAIN), ".json")) {
            List<ArchivalChunkDto> chunks = loadChunksFromFile(f);
            boolean removed = chunks.removeIf(c -> c.chunkId().equals(chunkId));
            if (removed) {
                // Find userId from remaining chunks or filename
                store.write(List.of(DOMAIN, f), new ChunkList(chunks));
                log.debugf("[FsArchival] Deleted chunk %s", chunkId);
                break;
            }
        }
    }

    @Override
    public void evictLowConfidence(double threshold, int minAgeDays) {
        Instant cutoff = Instant.now().minusSeconds(minAgeDays * 86400L);
        for (String f : store.list(List.of(DOMAIN), ".json")) {
            List<ArchivalChunkDto> chunks = loadChunksFromFile(f);
            int before = chunks.size();
            chunks.removeIf(c -> c.confidence() < threshold
                    && c.createdAt() != null
                    && c.createdAt().isBefore(cutoff));
            if (chunks.size() < before) {
                String userId = f.replace(".json", "");
                store.write(List.of(DOMAIN, f), new ChunkList(chunks));
                log.debugf("[FsArchival] Evicted %d low-confidence chunks for user %s", before - chunks.size(), userId);
            }
        }
    }

    private List<ArchivalChunkDto> loadChunks(String userId) {
        return loadChunksFromFile(userId + ".json");
    }

    private List<ArchivalChunkDto> loadChunksFromFile(String filename) {
        Optional<ChunkList> list = store.readOptional(List.of(DOMAIN, filename), ChunkList.class);
        return list.map(c -> new ArrayList<>(c.chunks())).orElseGet(ArrayList::new);
    }
}
