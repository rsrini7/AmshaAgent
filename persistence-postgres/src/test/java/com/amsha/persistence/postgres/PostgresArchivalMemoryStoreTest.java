package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalSearchQuery;
import com.amsha.persistence.postgres.jdbi.ArchivalMemoryJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresArchivalMemoryStoreTest {

    @Mock
    Jdbi jdbi;

    @Mock
    ArchivalMemoryJdbiDao dao;

    private PostgresArchivalMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new PostgresArchivalMemoryStore(jdbi);
    }

    @Test
    void testUpsertAndAccessCounts() {
        doAnswer(inv -> {
                    ExtensionConsumer consumer = inv.getArgument(1);
                    consumer.useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(ArchivalMemoryJdbiDao.class), any());

        ArchivalChunkDto chunk = new ArchivalChunkDto(
                "c-1", "usr-1", "content", "DOC", "sess-1", "msg-1", "tags", 0.9, 0, Instant.now(), Instant.now());
        store.upsert(chunk);
        verify(dao).upsert(chunk);

        store.incrementAccessCount("c-1");
        verify(dao).incrementAccessCount("c-1");

        store.incrementAccessCounts(List.of("c-1"));
        verify(dao).incrementAccessCounts(List.of("c-1"));

        store.delete("c-1");
        verify(dao).delete("c-1");

        store.evictLowConfidence(0.5, 30);
        verify(dao).evictLowConfidence(0.5, 30);
    }

    @Test
    void testSearchAndRetrievals() {
        when(jdbi.withExtension(eq(ArchivalMemoryJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });

        when(dao.searchAllTypes("usr-1", "q", 10)).thenReturn(List.of());
        ArchivalSearchQuery q1 = new ArchivalSearchQuery("usr-1", "q", 10, null, false, false, null);
        assertTrue(store.search(q1).isEmpty());

        when(dao.getById("c-1")).thenReturn(Optional.empty());
        assertFalse(store.getById("c-1").isPresent());

        when(dao.countByUserId("usr-1")).thenReturn(5L);
        assertEquals(5L, store.countByUserId("usr-1"));

        when(dao.listByUserId("usr-1", 10, 0)).thenReturn(List.of());
        assertTrue(store.listByUserId("usr-1", 10, 0).isEmpty());
    }

    @Test
    void theProtectedNoArgConstructorBuildsAnUnconfiguredInstance() {
        // Same package as the store, so the protected constructor is reachable without reflection.
        assertDoesNotThrow(() -> new PostgresArchivalMemoryStore());
    }

    @Test
    void testSearchWithChunkTypesUsesTheFilteredQuery() {
        when(jdbi.withExtension(eq(ArchivalMemoryJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });
        // The query carries a Set; the DAO takes a List, so the conversion is part of the contract.
        when(dao.searchByTypes("usr-1", "q", 10, List.of("DOC"))).thenReturn(List.of());

        ArchivalSearchQuery filtered = new ArchivalSearchQuery("usr-1", "q", 10, Set.of("DOC"), false, false, null);

        assertTrue(store.search(filtered).isEmpty());
        // A non-empty chunk-type filter must NOT fall back to the unfiltered query.
        verify(dao, never()).searchAllTypes(any(), any(), anyInt());
    }
}
