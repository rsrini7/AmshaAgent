package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.CompactionSummaryDto;
import com.amsha.persistence.postgres.jdbi.CompactionJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresCompactionStoreAdapterTest {

    @Mock
    Jdbi jdbi;

    @Mock
    CompactionJdbiDao dao;

    private PostgresCompactionStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PostgresCompactionStoreAdapter(jdbi);
    }

    @Test
    void testFindLatestBySessionId() {
        when(jdbi.withExtension(eq(CompactionJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });

        when(dao.findById("sess-1")).thenReturn(Optional.empty());
        assertFalse(adapter.findLatestBySessionId("sess-1").isPresent());
    }

    @Test
    void testUpsertAndDelete() {
        doAnswer(inv -> {
                    ExtensionConsumer consumer = inv.getArgument(1);
                    consumer.useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(CompactionJdbiDao.class), any());

        CompactionSummaryDto dto = new CompactionSummaryDto("sess-1", "summary", Instant.now(), 5, "usr-1");
        adapter.upsert(dto);
        verify(dao).upsert(eq("sess-1"), eq("summary"), any(), eq(5), eq("usr-1"));

        adapter.deleteBySessionId("sess-1");
        verify(dao).deleteBySessionId("sess-1");
    }

    @Test
    void testSearch() {
        when(jdbi.withExtension(eq(CompactionJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });

        assertTrue(adapter.search("usr-1", null, 10).isEmpty());

        when(dao.search("usr-1", "q", 10)).thenReturn(List.of());
        assertTrue(adapter.search("usr-1", "q", 10).isEmpty());
    }

    @Test
    void theProtectedNoArgConstructorBuildsAnUnconfiguredInstance() {
        // Same package as the adapter, so the protected constructor is reachable without reflection.
        assertDoesNotThrow(() -> new PostgresCompactionStoreAdapter());
    }
}
