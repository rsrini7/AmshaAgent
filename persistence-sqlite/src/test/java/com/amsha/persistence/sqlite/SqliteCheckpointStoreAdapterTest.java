package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.sqlite.jdbi.CheckpointJdbiDao;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SqliteCheckpointStoreAdapterTest {

    @Mock
    Jdbi jdbi;

    @Mock
    JdbiRetryWrapper retryWrapper;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    Handle handle;

    @Mock
    CheckpointJdbiDao dao;

    @Mock
    PreparedBatch preparedBatch;

    SqliteCheckpointStoreAdapter adapter;

    private Instant now;
    private CheckpointDto sampleDto;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new SqliteCheckpointStoreAdapter(jdbi, retryWrapper, objectMapper, 2);
        now = Instant.now();
        sampleDto = new CheckpointDto(
                1L,
                "sess-1",
                "coder",
                "IN_PROGRESS",
                "FULL",
                "{}",
                "cli",
                "cli",
                "goal",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                10,
                1,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                now,
                now);

        when(retryWrapper.withRetry(anyString(), anyInt(), anyLong(), any())).thenAnswer(inv -> {
            Callable<?> callable = inv.getArgument(3);
            return callable.call();
        });

        when(jdbi.inTransaction(any())).thenAnswer(inv -> {
            HandleCallback callback = inv.getArgument(0);
            return callback.withHandle(handle);
        });

        when(jdbi.withExtension(eq(CheckpointJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });

        when(handle.attach(CheckpointJdbiDao.class)).thenReturn(dao);
        when(handle.prepareBatch(anyString())).thenReturn(preparedBatch);
        when(dao.findAnyBySessionAndRole(anyString(), anyString())).thenReturn(Optional.of(sampleDto));
        when(dao.findById(anyLong())).thenReturn(Optional.of(sampleDto));
        when(dao.findBySessionAndRole(anyString(), anyString())).thenReturn(Optional.of(sampleDto));
        when(dao.claimForResume(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    @Test
    void testSave() {
        CheckpointDto result = adapter.save(sampleDto);
        assertNotNull(result);
        verify(dao).upsertCheckpoint(any());
    }

    @Test
    void testUpsertWithSteps() {
        var res = adapter.upsertWithSteps("sess-1", "coder", "{}", "FULL", 1, "goal", "{}");
        assertNotNull(res);
        verify(dao).upsertCheckpoint(any());
    }

    @Test
    void testUpsertAndFinalize() {
        ReasoningStepDto step = new ReasoningStepDto("{}", "act", "{}", "obs", 10, 20, true, null, null, null);
        var res = adapter.upsertAndFinalize("sess-1", "coder", "{}", "COMPLETED", List.of(step), "goal", "{}");
        assertNotNull(res);
        verify(dao).deleteSteps(1L);
        verify(preparedBatch).execute();
    }

    @Test
    void testSimpleDelegates() {
        Optional<CheckpointDto> byId = adapter.findById(1L);
        assertTrue(byId.isPresent());

        Optional<CheckpointDto> bySession = adapter.findBySessionAndRole("sess-1", "coder");
        assertTrue(bySession.isPresent());

        boolean claimed = adapter.claimForResume("sess-1", "coder");
        assertTrue(claimed);

        adapter.releaseResumeClaim("sess-1", "coder");
        verify(jdbi).useExtension(eq(CheckpointJdbiDao.class), any());
    }
}
