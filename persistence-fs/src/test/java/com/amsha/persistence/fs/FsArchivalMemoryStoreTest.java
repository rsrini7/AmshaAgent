package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalSearchQuery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FsArchivalMemoryStoreTest {

    @Mock
    FilesystemFileStore store;

    @InjectMocks
    FsArchivalMemoryStore memoryStore;

    private ArchivalChunkDto createChunk(String id, String userId, String content) {
        return new ArchivalChunkDto(
                id, userId, content, "FACT", "sess-1", "msg-1", "tag1", 0.9, 0, Instant.now(), Instant.now());
    }

    @Test
    void testUpsertAndSearch() {
        ArchivalChunkDto chunk = createChunk("chunk-1", "user-1", "Java is a programming language");
        when(store.readOptional(eq(List.of("archival", "user-1.json")), any())).thenReturn(Optional.empty());

        memoryStore.upsert(chunk);
        verify(store).write(eq(List.of("archival", "user-1.json")), any());

        FsArchivalMemoryStore.ChunkList list = new FsArchivalMemoryStore.ChunkList(List.of(chunk));
        when(store.readOptional(eq(List.of("archival", "user-1.json")), any())).thenReturn(Optional.of(list));

        List<ArchivalChunkDto> results =
                memoryStore.search(new ArchivalSearchQuery("user-1", "Java", 5, Set.of(), false, true, null));
        assertEquals(1, results.size());
        assertEquals("chunk-1", results.get(0).chunkId());
    }

    @Test
    void testGetByIdAndDelete() {
        ArchivalChunkDto chunk = createChunk("chunk-2", "user-2", "Quarkus is fast");
        FsArchivalMemoryStore.ChunkList list = new FsArchivalMemoryStore.ChunkList(List.of(chunk));

        when(store.list(List.of("archival"), ".json")).thenReturn(List.of("user-2.json"));
        when(store.readOptional(eq(List.of("archival", "user-2.json")), any())).thenReturn(Optional.of(list));

        Optional<ArchivalChunkDto> found = memoryStore.getById("chunk-2");
        assertTrue(found.isPresent());

        memoryStore.delete("chunk-2");
        verify(store).write(eq(List.of("archival", "user-2.json")), any());
    }

    @Test
    void testIncrementAccessCounts() {
        ArchivalChunkDto chunk = createChunk("chunk-3", "user-3", "Content");
        FsArchivalMemoryStore.ChunkList list = new FsArchivalMemoryStore.ChunkList(List.of(chunk));

        when(store.list(List.of("archival"), ".json")).thenReturn(List.of("user-3.json"));
        when(store.readOptional(eq(List.of("archival", "user-3.json")), any())).thenReturn(Optional.of(list));

        memoryStore.incrementAccessCounts(List.of("chunk-3"));
        verify(store).write(eq(List.of("archival", "user-3.json")), any());
    }
}
