package com.amsha.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.CheckpointUpsertResult;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.ToolDecision;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.AgentSentinels;
import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

/**
 * Unit tests for {@link StatefulAgentStoreImpl}: save/load orchestration, granular-step
 * priority, the double-resume guard interplay (instance-local + distributed), batch tool
 * decision evaluation, and query delegation.
 */
public class StatefulAgentStoreImplTest {

    @Mock
    CheckpointStorePort storeAdapter;

    @Mock
    CheckpointSerializer serializer;

    @Mock
    CheckpointTransitionService transitionService;

    @Spy
    ResumeCoordinationGuard resumeGuard = new ResumeCoordinationGuard();

    @InjectMocks
    StatefulAgentStoreImpl store;

    private AutoCloseable mocks;

    @BeforeEach
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    public void tearDown() throws Exception {
        mocks.close();
    }

    private CheckpointDto dto(long id) {
        CheckpointDto dto = mock(CheckpointDto.class);
        when(dto.id()).thenReturn(id);
        when(dto.sessionId()).thenReturn("sess-1");
        when(dto.agentRole()).thenReturn("coder");
        when(dto.status()).thenReturn(CheckpointStatus.IN_PROGRESS.name());
        when(dto.createdAt()).thenReturn(Instant.EPOCH);
        return dto;
    }

    private AgentCheckpoint checkpoint() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getId()).thenReturn(7L);
        when(cp.getSessionId()).thenReturn("sess-1");
        when(cp.getAgentRole()).thenReturn("coder");
        return cp;
    }

    @Test
    public void saveOrchestratesSerializerStoreStepsAndCreatedEvent() {
        ReasoningStep step = ReasoningStep.systemMessage("s");
        List<ReasoningStep> steps = List.of(step);
        when(serializer.serializeSteps(steps)).thenReturn("[]");
        when(serializer.toDto(any()))
                .thenReturn(new ReasoningStepDto("t", "calc", "{}", "obs", 1, 1, true, null, null, null));
        CheckpointDto saved = dto(9L);
        when(storeAdapter.upsertWithSteps(eq("sess-1"), eq("coder"), eq("[]"), anyString(), eq(1), eq("goal"), any()))
                .thenReturn(new CheckpointUpsertResult(saved, true));

        AgentCheckpoint result = store.save("sess-1", "coder", "user-1", AutonomyLevel.FULL, steps, "goal");

        assertEquals(9L, result.getId());
        verify(storeAdapter).saveSteps(eq(9L), anyList());
        verify(transitionService)
                .emitCreated(eq("sess-1"), eq("coder"), eq("user-1"), eq(AutonomyLevel.FULL), eq(9L), any());
    }

    @Test
    public void saveFallsBackToSupervisedAutonomyAndSkipsCreatedEventOnUpdate() {
        when(serializer.serializeSteps(anyList())).thenReturn("[]");
        var updated = new CheckpointUpsertResult(dto(3L), false);
        when(storeAdapter.upsertWithSteps(
                        anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), any()))
                .thenReturn(updated);

        store.save("sess-1", "coder", "user-1", null, List.of(), "goal");

        verify(storeAdapter)
                .upsertWithSteps(
                        eq("sess-1"),
                        eq("coder"),
                        eq("[]"),
                        eq(AutonomyLevel.SUPERVISED.toString()),
                        eq(0),
                        eq("goal"),
                        isNull());
        verify(transitionService, never()).emitCreated(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    public void loadMapsEmptyAndPresentResults() {
        assertTrue(store.load("missing", "coder").isEmpty());

        CheckpointDto dto = dto(5L);
        when(storeAdapter.findBySessionAndRole("sess-1", "coder")).thenReturn(Optional.of(dto));
        Optional<AgentCheckpoint> loaded = store.load("sess-1", "coder");
        assertTrue(loaded.isPresent());
        assertEquals(5L, loaded.get().getId());
    }

    @Test
    public void deserializeStepsPrefersGranularRowsThenStateJsonFallback() {
        AgentCheckpoint withId = checkpoint();
        ReasoningStepDto granularDto = new ReasoningStepDto("t", "calc", "{}", "obs", 1, 1, true, null, null, null);
        ReasoningStep granularStep = ReasoningStep.systemMessage("s");
        when(storeAdapter.loadSteps(7L)).thenReturn(List.of(granularDto));
        when(serializer.fromDto(granularDto)).thenReturn(granularStep);

        assertEquals(List.of(granularStep), store.deserializeSteps(withId));

        // Empty granular rows -> fallback to stateJson deserialization
        when(storeAdapter.loadSteps(7L)).thenReturn(List.of());
        ReasoningStep legacy = ReasoningStep.systemMessage("s");
        when(serializer.deserializeSteps(withId)).thenReturn(List.of(legacy));
        assertEquals(List.of(legacy), store.deserializeSteps(withId));

        assertTrue(store.deserializeSteps(null).isEmpty());
    }

    @Test
    public void saveStepsSkipsWhenIdOrStepsMissing() {
        store.saveSteps(null, List.of(ReasoningStep.systemMessage("s")));
        store.saveSteps(7L, null);
        verify(storeAdapter, never()).saveSteps(anyLong(), anyList());

        ReasoningStep step = ReasoningStep.systemMessage("s");
        when(serializer.toDto(step))
                .thenReturn(new ReasoningStepDto("t", "calc", "{}", "obs", 1, 1, true, null, null, null));
        store.saveSteps(7L, List.of(step));
        verify(storeAdapter).saveSteps(eq(7L), anyList());
    }

    @Test
    public void saveAndFinalizeEmitsCreatedThenFinalStatus() {
        ReasoningStep step = ReasoningStep.systemMessage("s");
        when(serializer.serializeSteps(anyList())).thenReturn("[]");
        when(serializer.toDto(any()))
                .thenReturn(new ReasoningStepDto("t", "calc", "{}", "obs", 1, 1, true, null, null, null));
        var savedDto = dto(11L);
        when(storeAdapter.upsertAndFinalize(
                        anyString(), anyString(), anyString(), anyString(), anyList(), anyString(), any()))
                .thenReturn(new CheckpointUpsertResult(savedDto, true));

        AgentCheckpoint result = store.saveAndFinalize(
                "sess-1",
                "coder",
                "user-1",
                AutonomyLevel.SUPERVISED,
                List.of(step),
                CheckpointStatus.COMPLETED,
                "goal");

        assertEquals(11L, result.getId());
        verify(transitionService).emitCreated(any(), any(), any(), any(), eq(11L), any());
        verify(transitionService)
                .emitFinalStatus(
                        eq("sess-1"), eq("coder"), eq("user-1"), eq(CheckpointStatus.COMPLETED), eq(11L), any());
    }

    @Test
    public void claimForResumeBlockedByInstanceLocalGuardFirst() {
        assertTrue(resumeGuard.markResuming("blocked"));

        assertFalse(store.claimForResume("blocked", "coder"));
        verify(storeAdapter, never()).claimForResume(anyString(), anyString());
        resumeGuard.clearResuming("blocked");
    }

    @Test
    public void claimForResumeMarksLocalGuardOnlyWhenDistributedClaimSucceeds() {
        when(storeAdapter.claimForResume("ok", "coder")).thenReturn(true);
        assertTrue(store.claimForResume("ok", "coder"));
        assertTrue(store.isBatchResuming("ok"));
        store.clearBatchResuming("ok");
        assertFalse(store.isBatchResuming("ok"));

        when(storeAdapter.claimForResume("denied", "coder")).thenReturn(false);
        assertFalse(store.claimForResume("denied", "coder"));
        assertFalse(store.isBatchResuming("denied"));
    }

    @Test
    public void releaseResumeClaimClearsBothLayers() {
        when(storeAdapter.claimForResume("rel", "coder")).thenReturn(true);
        store.claimForResume("rel", "coder");
        assertTrue(store.isBatchResuming("rel"));

        store.releaseResumeClaim("rel", "coder");
        verify(storeAdapter).releaseResumeClaim("rel", "coder");
        assertFalse(store.isBatchResuming("rel"));
    }

    @Test
    public void markResumingWithGoalAndGetActiveGoal() {
        assertTrue(store.markResuming("goal-sess", "Analyze thread dump"));
        assertTrue(store.isBatchResuming("goal-sess"));
        assertEquals(Optional.of("Analyze thread dump"), store.getActiveGoal("goal-sess"));

        // Second call fails
        assertFalse(store.markResuming("goal-sess", "Other task"));

        store.clearResuming("goal-sess");
        assertFalse(store.isBatchResuming("goal-sess"));
        assertEquals(Optional.empty(), store.getActiveGoal("goal-sess"));
    }

    @Test
    public void markFailedOverloadsDelegateToTransitionService() {
        AgentCheckpoint cp = checkpoint();

        store.markFailed(1L, "boom");
        store.markFailed(cp, "boom2");
        store.markFailed(3L, "boom3", "E", "trace", "RootCause");
        store.markFailed(cp, "boom4", "E2", "trace2", "RootCause2");
        store.markOrphaned(5L, "stale");
        store.markCompleted(6L);

        verify(transitionService).markFailed(1L, "boom", null, null, null, "unknown");
        verify(transitionService).markFailed(7L, "boom2", null, null, null, "unknown");
        verify(transitionService).markFailed(3L, "boom3", "E", "trace", "RootCause", "unknown");
        verify(transitionService).markFailed(7L, "boom4", "E2", "trace2", "RootCause2", "unknown");
        verify(transitionService).markOrphaned(5L, "stale", "unknown");
        verify(transitionService).markCompleted(6L, "unknown");
    }

    @Test
    public void archiveAndStampChannelInfoDelegateToStore() {
        store.archive(8L);
        store.stampChannelInfo(8L, "TELEGRAM", "42");
        verify(storeAdapter).archive(8L);
        verify(storeAdapter).stampChannelInfo(8L, "TELEGRAM", "42");
    }

    @Test
    public void isAllBatchToolsDecidedEvaluatesPendingCount() {
        // No awaiting checkpoint -> false
        when(storeAdapter.findLatestAwaitingApproval("s1")).thenReturn(Optional.empty());
        assertFalse(store.isAllBatchToolsDecided("s1", "coder"));

        // Role mismatch -> false
        CheckpointDto otherRole = dto(1L);
        when(otherRole.agentRole()).thenReturn("researcher");
        when(storeAdapter.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(otherRole));
        assertFalse(store.isAllBatchToolsDecided("s1", "coder"));

        // Null decisions JSON -> false
        CheckpointDto nullDecisions = dto(1L);
        when(nullDecisions.batchToolDecisions()).thenReturn(null);
        when(storeAdapter.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(nullDecisions));
        assertFalse(store.isAllBatchToolsDecided("s1", "coder"));

        // Pending present -> false; all decided -> true
        when(serializer.deserialize(anyString(), ArgumentMatchers.<TypeReference<Map<String, ToolDecision>>>any()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).contains("PENDING")
                        ? Map.of("t1", ToolDecision.PENDING, "t2", ToolDecision.APPROVED)
                        : Map.of("t1", ToolDecision.APPROVED, "t2", ToolDecision.DENIED));
        CheckpointDto withPending = dto(1L);
        when(withPending.batchToolDecisions()).thenReturn("{\"t1\":\"PENDING\",\"t2\":\"APPROVED\"}");
        when(storeAdapter.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(withPending));
        assertFalse(store.isAllBatchToolsDecided("s1", "coder"));

        CheckpointDto fullyDecided = dto(1L);
        when(fullyDecided.batchToolDecisions()).thenReturn("{\"t1\":\"APPROVED\",\"t2\":\"DENIED\"}");
        when(storeAdapter.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(fullyDecided));
        assertTrue(store.isAllBatchToolsDecided("s1", "coder"));
    }

    @Test
    public void isActiveStatusValidatesActiveStatuses() {
        assertTrue(StatefulAgentStore.isActiveStatus(CheckpointStatus.IN_PROGRESS));
        assertTrue(StatefulAgentStore.isActiveStatus(CheckpointStatus.RESUMING));
        assertTrue(StatefulAgentStore.isActiveStatus(CheckpointStatus.AWAITING_APPROVAL));
        assertTrue(StatefulAgentStore.isActiveStatus(CheckpointStatus.AWAITING_RETRY));

        assertFalse(StatefulAgentStore.isActiveStatus(CheckpointStatus.COMPLETED));
        assertFalse(StatefulAgentStore.isActiveStatus(CheckpointStatus.FAILED));
        assertFalse(StatefulAgentStore.isActiveStatus(CheckpointStatus.ARCHIVED));
        assertFalse(StatefulAgentStore.isActiveStatus(null));
    }

    @Test
    public void findLatestActiveDelegatedCheckpointFiltersAndFindsLatest() {
        String rootSession = "sess-root";
        String child1 = rootSession + AgentSentinels.DELEGATE_SESSION_SENTINEL + "researcher:1";
        String child2 = rootSession + AgentSentinels.DELEGATE_SESSION_SENTINEL + "researcher:2";
        String unrelated = "other-sess" + AgentSentinels.DELEGATE_SESSION_SENTINEL + "researcher:1";

        CheckpointDto rootDto = dto(10L);
        when(rootDto.sessionId()).thenReturn(rootSession);
        when(rootDto.updatedAt()).thenReturn(Instant.ofEpochMilli(1000));

        CheckpointDto child1Dto = dto(11L);
        when(child1Dto.sessionId()).thenReturn(child1);
        when(child1Dto.updatedAt()).thenReturn(Instant.ofEpochMilli(2000));

        CheckpointDto child2Dto = dto(12L);
        when(child2Dto.sessionId()).thenReturn(child2);
        when(child2Dto.updatedAt()).thenReturn(Instant.ofEpochMilli(3000));

        CheckpointDto unrelatedDto = dto(13L);
        when(unrelatedDto.sessionId()).thenReturn(unrelated);
        when(unrelatedDto.updatedAt()).thenReturn(Instant.ofEpochMilli(4000));

        when(storeAdapter.findActiveBySessionTree(rootSession))
                .thenReturn(List.of(rootDto, child1Dto, child2Dto, unrelatedDto));

        Optional<AgentCheckpoint> latest = store.findLatestActiveDelegatedCheckpoint(rootSession);
        assertTrue(latest.isPresent());
        assertEquals(12L, latest.get().getId());
        assertEquals(child2, latest.get().getSessionId());
    }

    @Test
    public void findActiveCheckpointsInSessionTreeDelegatesToStoreAdapter() {
        CheckpointDto rootDto = dto(10L);
        when(rootDto.sessionId()).thenReturn("tree-root");
        when(storeAdapter.findActiveBySessionTree("tree-root")).thenReturn(List.of(rootDto));

        List<AgentCheckpoint> list = store.findActiveCheckpointsInSessionTree("tree-root");
        assertEquals(1, list.size());
        assertEquals(10L, list.get(0).getId());
        verify(storeAdapter).findActiveBySessionTree("tree-root");
    }
}
