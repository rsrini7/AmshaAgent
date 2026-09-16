package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;

import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.RecallSearchQuery;
import com.amsha.persistence.postgres.jdbi.RecallMemoryJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresRecallMemoryStoreTest {

    @Mock
    Jdbi jdbi;

    @Mock
    RecallMemoryJdbiDao dao;

    private PostgresRecallMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new PostgresRecallMemoryStore(jdbi);
    }

    @Test
    void testInsertAndPurge() {
        doAnswer(inv -> {
                    ExtensionConsumer consumer = inv.getArgument(1);
                    consumer.useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(RecallMemoryJdbiDao.class), any());

        RecallMessageDto msg = new RecallMessageDto(
                "msg-1",
                "sess-1",
                "usr-1",
                1,
                "hash-1",
                "user",
                "USER_MESSAGE",
                "hello",
                null,
                null,
                false,
                false,
                Instant.now(),
                null);
        store.insertOrIgnore(msg);
        verify(dao).insertOrIgnore(msg);

        store.insertBatch(List.of(msg));
        verify(dao).insertBatch(List.of(msg));

        store.purgeExpired("usr-1", 30);
        verify(dao).purgeExpired("usr-1", 30);

        store.purgeExpiredAll(30);
        verify(dao).purgeExpiredAll(30);
    }

    @Test
    void testSearchAndCount() {
        when(jdbi.withExtension(eq(RecallMemoryJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });

        RecallSearchQuery query = new RecallSearchQuery("usr-1", "hello", "sess-1", 10, false, null);
        when(dao.search("usr-1", "sess-1", "hello", false, 10)).thenReturn(List.of());
        assertTrue(store.search(query).isEmpty());

        when(dao.countByUserId("usr-1")).thenReturn(3L);
        assertEquals(3L, store.countByUserId("usr-1"));

        assertFalse(store.getById("msg-1").isPresent());
    }

    @Test
    void theProtectedNoArgConstructorBuildsAnUnconfiguredInstance() {
        // Same package as the store, so the protected constructor is reachable without reflection.
        assertDoesNotThrow(() -> new PostgresRecallMemoryStore());
    }

    @Test
    void testInsertBatchIgnoresNullAndEmptyBatches() {
        store.insertBatch(null);
        store.insertBatch(List.of());

        // No stub is installed in this test, so this proves the guard ran before any connection was
        // requested rather than merely that the DAO happened to return quietly.
        verifyNoInteractions(jdbi);
    }
}
