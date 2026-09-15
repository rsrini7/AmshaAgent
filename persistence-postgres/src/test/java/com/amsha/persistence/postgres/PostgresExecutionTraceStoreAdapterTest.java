package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.amsha.persistence.api.ExecutionTraceDto;
import com.amsha.persistence.postgres.jdbi.ExecutionTraceJdbiDao;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresExecutionTraceStoreAdapterTest {

    @Mock
    Jdbi jdbi;

    @Mock
    ExecutionTraceJdbiDao dao;

    private PostgresExecutionTraceStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PostgresExecutionTraceStoreAdapter(jdbi);
    }

    private static String uid() {
        return UUID.randomUUID().toString();
    }

    private ExecutionTraceDto makeDto(String sessionId, String runId, int turnNumber, long sequence) {
        return new ExecutionTraceDto(
                uid(),
                runId,
                sessionId,
                turnNumber,
                sequence,
                "turn_start",
                "station_01_inbound",
                "RUNNING",
                "LOW",
                "Turn initialized",
                10,
                20,
                150L,
                1,
                1,
                Instant.now(),
                "REACT",
                1,
                Instant.now());
    }

    @Test
    void testSave() {
        doAnswer(inv -> {
                    ExtensionConsumer consumer = inv.getArgument(1);
                    consumer.useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(ExecutionTraceJdbiDao.class), any());

        ExecutionTraceDto dto = makeDto("sess-1", "sess-1:turn-1", 1, 0);
        assertDoesNotThrow(() -> adapter.save(dto));
        verify(dao).save(dto);
    }

    @Test
    void testFindByRunId() {
        when(jdbi.withExtension(eq(ExecutionTraceJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });
        when(dao.findByRunId("sess-1:turn-1")).thenReturn(List.of());

        assertTrue(adapter.findByRunId("sess-1:turn-1").isEmpty());
        assertTrue(adapter.findByRunId("").isEmpty());
        assertTrue(adapter.findByRunId(null).isEmpty());
    }

    @Test
    void testFindByRunIdBounded() {
        when(jdbi.withExtension(eq(ExecutionTraceJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });
        when(dao.findByRunIdBounded("sess-1:turn-1", 0, 10L, 0, 20)).thenReturn(List.of());

        assertTrue(adapter.findByRunIdBounded("sess-1:turn-1", 0, 10L, 0, 20).isEmpty());
    }

    @Test
    void testCountByRunId() {
        when(jdbi.withExtension(eq(ExecutionTraceJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });
        when(dao.countByRunId("sess-1:turn-1")).thenReturn(5L);

        assertEquals(5L, adapter.countByRunId("sess-1:turn-1"));
        assertEquals(0L, adapter.countByRunId(null));
    }

    @Test
    void testFindRunsBySessionId() {
        when(jdbi.withExtension(eq(ExecutionTraceJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });
        when(dao.findRunsBySessionId("sess-1", 0, 10)).thenReturn(List.of());

        assertTrue(adapter.findRunsBySessionId("sess-1", 0, 10).isEmpty());
        assertTrue(adapter.findRunsBySessionId(null, 0, 10).isEmpty());
    }

    @Test
    void testPruneBeforeAndDeleteBySessionId() {
        Instant now = Instant.now();
        when(jdbi.withExtension(eq(ExecutionTraceJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });
        when(dao.pruneBefore(now)).thenReturn(3);

        assertEquals(3L, adapter.pruneBefore(now));
        assertEquals(0L, adapter.pruneBefore(null));

        doAnswer(inv -> {
                    ExtensionConsumer consumer = inv.getArgument(1);
                    consumer.useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(ExecutionTraceJdbiDao.class), any());

        assertDoesNotThrow(() -> adapter.deleteBySessionId("sess-1"));
        verify(dao).deleteBySessionId("sess-1");
    }

    @Test
    void testPruneExcessRuns() {
        when(jdbi.withExtension(eq(ExecutionTraceJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });
        when(dao.pruneExcessRuns(50)).thenReturn(12);

        assertEquals(12L, adapter.pruneExcessRuns(50));
        assertEquals(0L, adapter.pruneExcessRuns(0));
        assertEquals(0L, adapter.pruneExcessRuns(-1));
    }

    // =========================================================================
    // saveBatch and the remaining blank guards
    // =========================================================================

    @Test
    @DisplayName("saveBatch skips null and empty lists without opening a handle")
    void testSaveBatchSkipsNullAndEmptyLists() {
        assertDoesNotThrow(() -> adapter.saveBatch(null));
        assertDoesNotThrow(() -> adapter.saveBatch(List.of()));

        // No stub is installed in this test, so this is a real assertion that the guard ran before any
        // connection was requested.
        verifyNoInteractions(jdbi);
    }

    @Test
    @DisplayName("saveBatch binds every DTO into one prepared batch and executes it once")
    void testSaveBatchBindsAndExecutesOneBatch() {
        Handle handle = mock(Handle.class);
        PreparedBatch batch = mock(PreparedBatch.class);
        when(handle.prepareBatch(anyString())).thenReturn(batch);
        when(batch.bindMethods(any())).thenReturn(batch);
        doAnswer(inv -> {
                    HandleConsumer<RuntimeException> consumer = inv.getArgument(0);
                    consumer.useHandle(handle);
                    return null;
                })
                .when(jdbi)
                .useHandle(any());

        ExecutionTraceDto first = makeDto("sess-1", "sess-1:turn-1", 1, 0);
        ExecutionTraceDto second = makeDto("sess-1", "sess-1:turn-1", 1, 1);

        adapter.saveBatch(List.of(first, second));

        verify(batch).bindMethods(first);
        verify(batch).bindMethods(second);
        verify(batch, times(2)).add();
        verify(batch).execute();
    }

    @Test
    @DisplayName("blank run and session ids short-circuit without querying")
    void testBlankArgumentsShortCircuit() {
        assertTrue(adapter.findByRunIdBounded(null, 0, null, 0, 10).isEmpty());
        assertTrue(adapter.findByRunIdBounded("   ", 0, null, 0, 10).isEmpty());
        assertDoesNotThrow(() -> adapter.deleteBySessionId(null));
        assertDoesNotThrow(() -> adapter.deleteBySessionId("   "));

        verifyNoInteractions(jdbi);
    }

    @Test
    @DisplayName("the protected no-arg constructor builds an unconfigured instance")
    void theProtectedNoArgConstructorBuildsAnUnconfiguredInstance() {
        // Same package as the adapter, so the protected constructor is reachable without reflection.
        assertDoesNotThrow(() -> new PostgresExecutionTraceStoreAdapter());
    }
}
