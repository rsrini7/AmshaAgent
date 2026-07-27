package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.MemoryRecordType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FsExtractedMemoryStoreTest {

    @Mock
    FilesystemFileStore fileStore;

    FsExtractedMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new FsExtractedMemoryStore();
        store.store = fileStore;
    }

    @Test
    void testInsertBatchAndGetBySession() {
        ExtractedMemory m1 = new ExtractedMemory(
                "mem-1",
                "sess-1",
                "user-1",
                MemoryRecordType.USER_PREFERENCE,
                "fact 1",
                0.9,
                "hash-1",
                "source",
                Instant.now());

        when(fileStore.readOptional(any(), eq(FsExtractedMemoryStore.MemoryBatch.class)))
                .thenReturn(Optional.empty());

        int inserted = store.insertBatch(List.of(m1));
        assertEquals(1, inserted);
        verify(fileStore).write(any(), any());
    }

    @Test
    void testGetByUserAndListUserIds() {
        ExtractedMemory m2 = new ExtractedMemory(
                "mem-2", "sess-2", "user-2", MemoryRecordType.FACT, "fact 2", 0.8, "hash-2", "source", Instant.now());

        when(fileStore.list(List.of("extracted"), ".json")).thenReturn(List.of("sess-2.json"));
        when(fileStore.readOptional(List.of("extracted", "sess-2.json"), FsExtractedMemoryStore.MemoryBatch.class))
                .thenReturn(Optional.of(new FsExtractedMemoryStore.MemoryBatch(List.of(m2))));

        List<ExtractedMemory> byUser = store.getByUser("user-2", 5);
        assertEquals(1, byUser.size());

        List<String> users = store.listUserIds(5);
        assertEquals(List.of("user-2"), users);
        assertTrue(store.existsByHash("sess-2", "hash-2"));
    }
}
