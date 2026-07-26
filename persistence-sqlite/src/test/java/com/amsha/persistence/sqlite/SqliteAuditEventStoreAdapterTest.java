package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.AuditEventDto;
import com.amsha.persistence.sqlite.jdbi.AuditJdbiDao;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.statement.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class SqliteAuditEventStoreAdapterTest {

    @Mock
    Jdbi jdbi;

    @Mock
    AuditJdbiDao dao;

    @Mock
    Handle handle;

    @Mock(answer = Answers.RETURNS_SELF)
    Update update;

    private SqliteAuditEventStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SqliteAuditEventStoreAdapter(jdbi);
        lenient().when(jdbi.withExtension(any(), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });
    }

    @Test
    void testSave() throws Exception {
        doAnswer(inv -> {
                    HandleConsumer consumer = inv.getArgument(0);
                    when(handle.createUpdate(anyString())).thenReturn(update);
                    consumer.useHandle(handle);
                    return null;
                })
                .when(jdbi)
                .useHandle(any());

        UUID id = UUID.randomUUID();
        AuditEventDto dto = new AuditEventDto(
                id,
                "sess-1",
                "usr-1",
                "coder",
                "hash1",
                "FINISH",
                "LOOP_FINISH",
                "ok",
                100,
                50,
                new BigDecimal("0.01"),
                Instant.now(),
                5,
                "1.0",
                "idem-1",
                1L,
                "curr-hash",
                "prev-hash");

        assertDoesNotThrow(() -> adapter.save(dto));
    }

    @Test
    void testFindLatestHash() {
        when(dao.findLatestHash("sess-1")).thenReturn(Optional.of("hash-xyz"));
        assertEquals(Optional.of("hash-xyz"), adapter.findLatestHash("sess-1"));
    }

    @Test
    void testFindBySessionId() {
        when(dao.findBySessionId("sess-1")).thenReturn(List.of());
        assertTrue(adapter.findBySessionId("sess-1").isEmpty());
    }

    @Test
    void testCountsAndMaxSequence() {
        when(dao.countByIdempotencyKey("idem-1")).thenReturn(3L);
        when(dao.countEventsByType("sess-1", "LOOP_FINISH")).thenReturn(2L);
        when(dao.getMaxSequence("sess-1")).thenReturn(5L);

        assertEquals(3L, adapter.countByIdempotencyKey("idem-1"));
        assertEquals(2L, adapter.countEventsByType("sess-1", "LOOP_FINISH"));
        assertEquals(5L, adapter.getMaxSequence("sess-1"));
    }

    @Test
    void testFindByIdAndFiltered() {
        UUID id = UUID.randomUUID();
        when(dao.findById(id)).thenReturn(Optional.empty());
        assertTrue(adapter.findById(id).isEmpty());
        assertTrue(adapter.findById(null).isEmpty());

        when(dao.findFiltered(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        assertTrue(adapter.findAll("sess-1", "type1", Instant.now(), Instant.now(), 0, 10)
                .isEmpty());

        when(dao.countFiltered(any(), any(), any(), any())).thenReturn(0L);
        assertEquals(0L, adapter.countAll("sess-1", "type1", Instant.now(), Instant.now()));
    }
}
