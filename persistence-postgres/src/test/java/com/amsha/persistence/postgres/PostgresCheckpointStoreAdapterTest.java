package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.postgres.jdbi.CheckpointJdbiDao;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
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
class PostgresCheckpointStoreAdapterTest {

    @Mock
    Jdbi jdbi;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    Handle handle;

    @Mock
    CheckpointJdbiDao dao;

    @Mock
    PreparedBatch preparedBatch;

    PostgresCheckpointStoreAdapter adapter;

    private Instant now;
    private CheckpointDto sampleDto;

    @BeforeEach
    void setUp() {
        adapter = new PostgresCheckpointStoreAdapter(jdbi, objectMapper, 2);
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

        when(jdbi.inTransaction(any())).thenAnswer(inv -> {
            HandleCallback callback = inv.getArgument(0);
            return callback.withHandle(handle);
        });

        when(jdbi.withExtension(eq(CheckpointJdbiDao.class), any())).thenAnswer(inv -> {
            ExtensionCallback callback = inv.getArgument(1);
            return callback.withExtension(dao);
        });

        doAnswer(inv -> {
                    ExtensionConsumer consumer = inv.getArgument(1);
                    consumer.useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(CheckpointJdbiDao.class), any());

        when(handle.attach(CheckpointJdbiDao.class)).thenReturn(dao);
        when(handle.prepareBatch(anyString())).thenReturn(preparedBatch);
        when(dao.findAnyBySessionAndRole(anyString(), anyString())).thenReturn(Optional.of(sampleDto));
        when(dao.findById(anyLong())).thenReturn(Optional.of(sampleDto));
        when(dao.findBySessionAndRole(anyString(), anyString())).thenReturn(Optional.of(sampleDto));
        when(dao.claimForResume(anyString(), anyString(), any(), any())).thenReturn(true);
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

    @Test
    void testQueriesAndTransitions() {
        when(dao.findActiveBySession("sess-1")).thenReturn(List.of(sampleDto));
        when(dao.findByStatusAndSessionIds("IN_PROGRESS", List.of("sess-1"), 10, 0))
                .thenReturn(List.of(sampleDto));
        when(dao.countByStatusAndSessionIds("IN_PROGRESS", List.of("sess-1"))).thenReturn(1L);
        when(dao.findByStatus("IN_PROGRESS")).thenReturn(List.of(sampleDto));
        when(dao.findAllActive()).thenReturn(List.of(sampleDto));
        when(dao.findLatestAwaiting("sess-1")).thenReturn(Optional.of(sampleDto));
        when(dao.findLatestAwaitingBySegment("seg")).thenReturn(Optional.of(sampleDto));
        when(dao.findLatestBySession("sess-1")).thenReturn(Optional.of(sampleDto));
        when(dao.findLatestNonArchived("sess-1")).thenReturn(Optional.of(sampleDto));
        when(dao.findLatestBySessionAndStatus("sess-1", "IN_PROGRESS")).thenReturn(Optional.of(sampleDto));
        when(dao.hasPendingApprovalForChat("chat-1")).thenReturn(true);
        when(dao.atomicTransition(eq(1L), anyString(), any(), anyString())).thenReturn(true);
        when(dao.claimForRetry(eq(1L), any())).thenReturn(true);

        assertEquals(1, adapter.findActiveBySession("sess-1").size());
        assertEquals(
                1,
                adapter.findByStatusAndSessionIds("IN_PROGRESS", List.of("sess-1"), 10, 0)
                        .size());
        assertEquals(1L, adapter.countByStatusAndSessionIds("IN_PROGRESS", List.of("sess-1")));
        assertEquals(1, adapter.findByStatus("IN_PROGRESS").size());
        assertEquals(1, adapter.findAllActive().size());
        assertTrue(adapter.findLatestAwaitingApproval("sess-1").isPresent());
        assertTrue(adapter.findLatestAwaitingApprovalBySessionContaining("seg").isPresent());
        assertTrue(adapter.findLatestBySession("sess-1").isPresent());
        assertTrue(adapter.findLatestNonArchivedBySession("sess-1").isPresent());
        assertTrue(adapter.findLatestBySessionAndStatus("sess-1", "IN_PROGRESS").isPresent());
        assertTrue(adapter.hasPendingApproval("sess-1"));
        assertTrue(adapter.hasPendingApprovalForChat("chat-1"));

        adapter.transitionStatus(1L, "COMPLETED");
        assertTrue(adapter.atomicTransition(1L, "IN_PROGRESS", "COMPLETED"));
        adapter.markFailed(1L, "reason", "ERR", "trace", "Root");
        adapter.markCompleted(1L);
        adapter.markOrphaned(1L, "timeout");
        adapter.archive(1L);
        assertTrue(adapter.claimForRetry(1L));
    }

    @Test
    void testStepsAndBatchOperations() {
        doAnswer(inv -> {
                    HandleConsumer consumer = inv.getArgument(0);
                    consumer.useHandle(handle);
                    return null;
                })
                .when(jdbi)
                .useTransaction(any());

        ReasoningStepDto step = new ReasoningStepDto("{}", "tool_call", "{}", "obs", 10, 20, true, null, null, null);
        adapter.saveSteps(1L, List.of(step));
        verify(dao).deleteSteps(1L);

        when(dao.loadSteps(1L)).thenReturn(List.of(step));
        List<ReasoningStepDto> steps = adapter.loadSteps(1L);
        assertEquals(1, steps.size());

        adapter.deleteSteps(1L);

        // Atomic update batch tool decision
        when(dao.atomicUpdateBatchDecision(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);
        CheckpointDto awaitingDto = new CheckpointDto(
                1L,
                "sess-1",
                "coder",
                "AWAITING_APPROVAL",
                "FULL",
                "{}",
                "cli",
                "cli",
                "goal",
                null,
                null,
                null,
                null,
                "{\"tool1\":\"PENDING\"}",
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
        when(dao.findAnyBySessionAndRole("sess-1", "coder")).thenReturn(Optional.of(awaitingDto));

        boolean updated = adapter.atomicUpdateBatchToolDecision("sess-1", "coder", "tool1", "APPROVED");
        assertTrue(updated);
    }

    @Test
    void testClaimForResumeAndReleaseResumeClaim() {
        when(dao.claimForResume(eq("sess-1"), eq("coder"), any(Instant.class), any(Instant.class)))
                .thenReturn(true);
        assertTrue(adapter.claimForResume("sess-1", "coder"));

        assertDoesNotThrow(() -> adapter.releaseResumeClaim("sess-1", "coder"));
        verify(dao).releaseResumeClaim(eq("sess-1"), eq("coder"), any(Instant.class));

        // atomicUpdateBatchToolDecision with missing checkpoint
        when(dao.findAnyBySessionAndRole("missing-sess", "coder")).thenReturn(Optional.empty());
        assertFalse(adapter.atomicUpdateBatchToolDecision("missing-sess", "coder", "tool1", "APPROVED"));
    }
}
