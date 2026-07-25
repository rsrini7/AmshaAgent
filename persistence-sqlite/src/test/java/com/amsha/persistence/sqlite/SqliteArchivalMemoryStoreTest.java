package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.amsha.core.memory.tiered.TieredBackend;
import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalSearchQuery;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteArchivalMemoryStoreTest {

    @Inject
    @TieredBackend("sqlite")
    SqliteArchivalMemoryStore store;

    @Test
    void testArchivalMemoryStoreOperations() {
        String userId = "user-archival-" + UUID.randomUUID();
        String chunkId = "chunk-" + UUID.randomUUID();

        ArchivalChunkDto chunk = new ArchivalChunkDto(
                chunkId,
                userId,
                "Archival memory test document content",
                "DOCUMENT",
                "session-1",
                "msg-1",
                "tag1,tag2",
                0.95,
                1,
                Instant.now(),
                Instant.now());

        store.upsert(chunk);

        Optional<ArchivalChunkDto> fetchedOpt = store.getById(chunkId);
        assertTrue(fetchedOpt.isPresent());
        ArchivalChunkDto fetched = fetchedOpt.get();
        assertEquals(chunkId, fetched.chunkId());
        assertEquals(userId, fetched.userId());
        assertEquals("Archival memory test document content", fetched.content());
        assertEquals(0.95, fetched.confidence());

        assertEquals(1, store.countByUserId(userId));

        // Increment access counts
        store.incrementAccessCount(chunkId);
        store.incrementAccessCounts(List.of(chunkId));

        ArchivalChunkDto accessed = store.getById(chunkId).orElseThrow();
        assertEquals(3, accessed.accessCount());

        // BM25 Search
        ArchivalSearchQuery searchQuery =
                new ArchivalSearchQuery(userId, "Archival memory", 5, Set.of("DOCUMENT"), false, true, null);
        List<ArchivalChunkDto> results = store.search(searchQuery);
        assertNotNull(results);

        // Evict low confidence & delete
        store.evictLowConfidence(0.5, 0);
        store.delete(chunkId);
        assertTrue(store.getById(chunkId).isEmpty());
    }
}
