package com.arcclaw.core.memory.tiered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.arcclaw.persistence.api.ArchivalChunkDto;
import com.arcclaw.persistence.api.ArchivalMemoryStorePort;
import com.arcclaw.persistence.api.ArchivalSearchQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ArchivalMemoryServiceTest {

    @Mock
    ArchivalMemoryStorePort store;

    ArchivalMemoryService service;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        service = new ArchivalMemoryService();
        service.store = store;
    }

    @Test
    void testUpsert_delegatesToStore() {
        ArchivalChunkDto chunk = new ArchivalChunkDto(
            "c1", "user1", "Some knowledge", "FACT",
            "session1", "msg1", "tag1",
            0.8, 0, Instant.now(), Instant.now());

        service.upsert(chunk);
        verify(store).upsert(chunk);
    }

    @Test
    void testSearch_delegatesToStore() {
        ArchivalSearchQuery query = new ArchivalSearchQuery(
            "user1", "knowledge query", 5, Set.of(), false, true, null);
        ArchivalChunkDto chunk = new ArchivalChunkDto(
            "c1", "user1", "Some knowledge", "FACT",
            "session1", "msg1", "tag1",
            0.8, 0, Instant.now(), Instant.now());

        when(store.search(any())).thenReturn(List.of(chunk));
        List<ArchivalChunkDto> results = service.search(query);

        assertEquals(1, results.size());
        assertEquals("c1", results.get(0).chunkId());
        verify(store).search(query);
    }

    @Test
    void testGetById_delegatesToStore() {
        ArchivalChunkDto chunk = new ArchivalChunkDto(
            "c1", "user1", "Some knowledge", "FACT",
            "session1", "msg1", "tag1",
            0.8, 0, Instant.now(), Instant.now());

        when(store.getById("c1")).thenReturn(Optional.of(chunk));
        Optional<ArchivalChunkDto> result = service.getById("c1");

        assertTrue(result.isPresent());
        assertEquals("c1", result.get().chunkId());
    }

    @Test
    void testDelete_delegatesToStore() {
        service.delete("c1");
        verify(store).delete("c1");
    }
}
