package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.AuditEventDto;
import com.amsha.persistence.postgres.jdbi.AuditJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresAuditEventStoreAdapterTest {

    @Mock
    Jdbi jdbi;

    @Mock
    AuditJdbiDao dao;

    private PostgresAuditEventStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PostgresAuditEventStoreAdapter(jdbi);
    }

    @Test
    void testSave() {
        doAnswer(inv -> {
                    ExtensionConsumer consumer = inv.getArgument(1);
                    consumer.useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(AuditJdbiDao.class), any());

        AuditEventDto dto = new AuditEventDto(
                UUID.randomUUID(),
                "sess-1",
                "usr-1",
                "coder",
                "goal-hash",
                "SUCCESS",
                "LLM_CALL",
                "detail",
                10,
                20,
                BigDecimal.ZERO,
                Instant.now(),
                1,
                "0.1.0",
                "idem-1",
                1L,
                "hash",
                "prev-hash");

        assertDoesNotThrow(() -> adapter.save(dto));
        verify(dao).save(dto);
    }

    @Test
    void testFindLatestHash() {
        when(jdbi.withExtension(eq(AuditJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });
        when(dao.findLatestHash("sess-1")).thenReturn(Optional.of("hash-1"));

        assertEquals(Optional.of("hash-1"), adapter.findLatestHash("sess-1"));
    }

    @Test
    void testFindBySessionId() {
        when(jdbi.withExtension(eq(AuditJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });
        when(dao.findBySessionId("sess-1")).thenReturn(List.of());

        assertTrue(adapter.findBySessionId("sess-1").isEmpty());
    }

    @Test
    void testCountsAndMaxSequence() {
        when(jdbi.withExtension(eq(AuditJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });
        when(dao.countByIdempotencyKey("idem-1")).thenReturn(1L);
        when(dao.countEventsByType("sess-1", "LLM_CALL")).thenReturn(5L);
        when(dao.getMaxSequence("sess-1")).thenReturn(10L);

        assertEquals(1L, adapter.countByIdempotencyKey("idem-1"));
        assertEquals(5L, adapter.countEventsByType("sess-1", "LLM_CALL"));
        assertEquals(10L, adapter.getMaxSequence("sess-1"));
    }

    @Test
    void testFindById() {
        assertFalse(adapter.findById(null).isPresent());

        UUID id = UUID.randomUUID();
        when(jdbi.withExtension(eq(AuditJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });
        when(dao.findById(id)).thenReturn(Optional.empty());

        assertFalse(adapter.findById(id).isPresent());
    }

    @Test
    void testFilteredSearch() {
        Instant now = Instant.now();
        when(jdbi.withExtension(eq(AuditJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });
        when(dao.findFiltered("sess-1", "LLM_CALL", now, now, 0, 10)).thenReturn(List.of());
        when(dao.countFiltered("sess-1", "LLM_CALL", now, now)).thenReturn(0L);

        assertTrue(adapter.findAll("sess-1", "LLM_CALL", now, now, 0, 10).isEmpty());
        assertEquals(0L, adapter.countAll("sess-1", "LLM_CALL", now, now));
    }
}
