package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointUpsertResult;
import com.amsha.persistence.api.ReasoningStepDto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FsCheckpointStoreAdapterTest {

    @Mock
    FilesystemFileStore store;

    @Mock
    FsCheckpointHelper helper;

    @InjectMocks
    FsCheckpointStoreAdapter adapter;

    private CheckpointDto createDto(Long id, String session, String role, String status) {
        return new CheckpointDto(
                id,
                session,
                role,
                status,
                "FULL",
                "{}",
                "chat-1",
                "telegram",
                "goal",
                null,
                null,
                null,
                null,
                "{\"tool\":\"PENDING\"}",
                null,
                null,
                10,
                0,
                null,
                null,
                "{}",
                null,
                null,
                null,
                null,
                0,
                Instant.now(),
                Instant.now());
    }

    @Test
    void testSaveNewCheckpoint() {
        when(helper.nextId()).thenReturn(100L);
        CheckpointDto dto = createDto(null, "sess-1", "DEFAULT", "IN_PROGRESS");

        CheckpointDto saved = adapter.save(dto);
        assertEquals(100L, saved.id());
        verify(store).write(eq(List.of("checkpoints", "sess-1", "DEFAULT.json")), any(CheckpointDto.class));
    }

    @Test
    void testSaveExistingCheckpoint() {
        CheckpointDto dto = createDto(100L, "sess-1", "DEFAULT", "IN_PROGRESS");
        CheckpointDto saved = adapter.save(dto);
        assertEquals(100L, saved.id());
        verify(store).write(eq(List.of("checkpoints", "sess-1", "DEFAULT.json")), any(CheckpointDto.class));
    }

    @Test
    void testFindActiveBySession() {
        CheckpointDto active = createDto(1L, "sess-1", "r1", "IN_PROGRESS");
        CheckpointDto completed = createDto(2L, "sess-1", "r2", "COMPLETED");

        when(helper.loadSession("sess-1")).thenReturn(List.of(active, completed));

        List<CheckpointDto> list = adapter.findActiveBySession("sess-1");
        assertEquals(1, list.size());
        assertEquals("IN_PROGRESS", list.get(0).status());
    }

    @Test
    void testFindByIdAndSessionRole() {
        CheckpointDto cp = createDto(10L, "sess-1", "role-1", "IN_PROGRESS");
        when(helper.loadAll()).thenReturn(List.of(cp));
        when(helper.findBySessionAndRole("sess-1", "role-1")).thenReturn(Optional.of(cp));

        assertTrue(adapter.findById(10L).isPresent());
        assertTrue(adapter.findBySessionAndRole("sess-1", "role-1").isPresent());
    }

    @Test
    void testTransitionsAndClaims() {
        CheckpointDto cp = createDto(10L, "sess-1", "role-1", "AWAITING_APPROVAL");
        lenient().when(helper.loadAll()).thenReturn(List.of(cp));
        when(helper.findBySessionAndRole("sess-1", "role-1")).thenReturn(Optional.of(cp));

        assertTrue(adapter.atomicTransition(10L, "AWAITING_APPROVAL", "RESUMING"));
        assertTrue(adapter.claimForResume("sess-1", "role-1"));

        adapter.releaseResumeClaim("sess-1", "role-1");
        adapter.markCompleted(10L);
        adapter.markFailed(10L, "err", "CODE", "trace", "Class");
        adapter.markOrphaned(10L, "orphan");
        adapter.archive(10L);
        adapter.stampChannelInfo(10L, "web", "platform");
    }

    @Test
    void testStepsAndPurge() {
        ReasoningStepDto step =
                new ReasoningStepDto("thought-1", "action-1", "{}", "obs-1", 100, 50, true, null, null, null);
        CheckpointDto cp = createDto(10L, "sess-1", "role-1", "IN_PROGRESS");

        lenient().when(helper.nextId()).thenReturn(10L);
        lenient().when(helper.loadAll()).thenReturn(List.of(cp));

        adapter.saveCheckpointWithSteps(cp, List.of(step));
        adapter.saveSteps(10L, List.of(step));
        adapter.deleteSteps(10L);
        adapter.deleteCheckpoint("sess-1", "role-1");
        adapter.purgeOldChatHistory(Instant.now().plusSeconds(3600));

        verify(helper, atLeastOnce()).persistSteps(eq(10L), any());
    }

    @Test
    void testUpserts() {
        CheckpointDto cp = createDto(10L, "sess-1", "role-1", "IN_PROGRESS");
        when(helper.findBySessionAndRole("sess-1", "role-1")).thenReturn(Optional.of(cp));

        CheckpointUpsertResult res1 = adapter.upsertWithSteps("sess-1", "role-1", "{}", "FULL", 1, "goal", "{}");
        assertFalse(res1.created());

        CheckpointUpsertResult res2 =
                adapter.upsertAndFinalize("sess-1", "role-1", "{}", "COMPLETED", List.of(), "goal", "{}");
        assertFalse(res2.created());
    }

    @Test
    void testQueriesAndCounts() {
        CheckpointDto cp = createDto(10L, "sess-1", "role-1", "AWAITING_APPROVAL");
        when(helper.loadAll()).thenReturn(List.of(cp));
        when(helper.loadSession("sess-1")).thenReturn(List.of(cp));
        when(helper.findBySessionAndRole("sess-1", "role-1")).thenReturn(Optional.of(cp));

        assertEquals(1, adapter.countByStatusAndSessionIds("AWAITING_APPROVAL", List.of("sess-1")));
        assertEquals(
                1,
                adapter.findByStatusAndSessionIds("AWAITING_APPROVAL", List.of("sess-1"), 10, 0)
                        .size());
        assertTrue(adapter.isSessionActive("sess-1"));
        assertTrue(adapter.hasPendingApproval("sess-1"));
        assertTrue(adapter.findLatestBySession("sess-1").isPresent());
        assertTrue(adapter.findLatestNonArchivedBySession("sess-1").isPresent());
        assertTrue(adapter.findLatestBySessionAndStatus("sess-1", "AWAITING_APPROVAL")
                .isPresent());
        assertTrue(adapter.findLatestAwaitingApprovalBySessionContaining("sess").isPresent());
        assertTrue(adapter.atomicUpdateBatchToolDecision("sess-1", "role-1", "tool", "APPROVED"));
    }
}
