package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.arcclaw.core.agent.loop.CheckpointResumeCoordinator.ResumeDecision;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.domain.model.AgentCheckpointRecord;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S1192")
class CheckpointResumeCoordinatorTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    AuditService auditService;

    @Mock
    ChannelPluginRegistry channelPluginRegistry;

    @Mock
    ChatMemoryWriter chatMemoryWriter;

    CheckpointResumeCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new CheckpointResumeCoordinator();
        coordinator.agentStore = agentStore;
        coordinator.auditService = auditService;
        coordinator.channelPluginRegistry = channelPluginRegistry;
        coordinator.chatMemoryWriter = chatMemoryWriter;
    }

    private AgentCheckpointRecord checkpoint(String sessionId, String role, CheckpointStatus status) {
        var cp = new AgentCheckpointRecord();
        cp.setSessionId(sessionId);
        cp.setAgentRole(role);
        cp.setStatus(status);
        cp.setIterationsUsed(3);
        cp.setContextDataJson("{\"key\":\"val\"}");
        return cp;
    }

    // ── Fresh start paths ───────────────────────────────────────

    @Test
    void resolveResumePath_WhenNoCheckpoint_ReturnsFreshStart() {
        when(agentStore.findLatestNonArchivedCheckpoint("sess-1")).thenReturn(Optional.empty());
        assertTrue(
                coordinator.resolveResumePath("sess-1", "coordinator", "user-1", null)
                        instanceof ResumeDecision.FreshStart);
    }

    @Test
    void resolveResumePath_WhenTerminalStatusWithRoleMismatch_ReturnsFreshStart() {
        var cp = checkpoint("sess-1", "coder", CheckpointStatus.COMPLETED);
        when(agentStore.findLatestNonArchivedCheckpoint("sess-1")).thenReturn(Optional.of(cp));

        assertTrue(
                coordinator.resolveResumePath("sess-1", "coordinator", "user-1", null)
                        instanceof ResumeDecision.FreshStart);
    }

    // ── IN_PROGRESS ─────────────────────────────────────────────

    @Test
    void resolveResumePath_WhenInProgress_ReturnsResumedHistory() {
        var cp = checkpoint("sess-1", "coordinator", CheckpointStatus.IN_PROGRESS);
        var steps = List.of(ReasoningStep.pending("", "test", Map.of()));
        when(agentStore.findLatestNonArchivedCheckpoint("sess-1")).thenReturn(Optional.of(cp));
        when(agentStore.deserializeSteps(cp)).thenReturn(steps);

        ResumeDecision decision = coordinator.resolveResumePath("sess-1", "coordinator", "user-1", null);

        assertTrue(decision instanceof ResumeDecision.ResumedHistory);
        var rh = (ResumeDecision.ResumedHistory) decision;
        assertEquals(steps, rh.history());
        assertEquals(3, rh.iterationsUsed());
        assertEquals("coordinator", rh.checkpointRole());
        assertEquals("{\"key\":\"val\"}", rh.contextDataJson());
    }

    // ── AWAITING_APPROVAL with resume signal ────────────────────

    @Test
    void resolveResumePath_WhenAwaitingApprovalWithResumeSignal_ReturnsResumedHistory() {
        var cp = checkpoint("sess-1", "coordinator", CheckpointStatus.AWAITING_APPROVAL);
        var ctx = mock(MessageContext.class);
        when(ctx.isResumeSignal()).thenReturn(true);
        var steps = List.of(ReasoningStep.pending("", "FINISH", Map.of("answer", "done")));
        when(agentStore.findLatestNonArchivedCheckpoint("sess-1")).thenReturn(Optional.of(cp));
        when(agentStore.deserializeSteps(cp)).thenReturn(steps);

        ResumeDecision decision = coordinator.resolveResumePath("sess-1", "coordinator", "user-1", ctx);

        assertTrue(decision instanceof ResumeDecision.ResumedHistory);
    }

    @Test
    void resolveResumePath_WhenResumingStatus_ReturnsResumedHistory() {
        var cp = checkpoint("sess-1", "coordinator", CheckpointStatus.RESUMING);
        when(agentStore.findLatestNonArchivedCheckpoint("sess-1")).thenReturn(Optional.of(cp));
        when(agentStore.deserializeSteps(cp)).thenReturn(List.of());

        ResumeDecision decision = coordinator.resolveResumePath("sess-1", "coordinator", "user-1", null);

        assertTrue(decision instanceof ResumeDecision.ResumedHistory);
    }

    // ── AWAITING_APPROVAL without resume signal ─────────────────

    @Test
    void resolveResumePath_WhenAwaitingApprovalWithoutResumeSignal_ReturnsAbortedStale() {
        var cp = checkpoint("sess-1", "coordinator", CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName("search");
        var ctx = mock(MessageContext.class);
        when(ctx.isResumeSignal()).thenReturn(false);
        when(agentStore.findLatestNonArchivedCheckpoint("sess-1")).thenReturn(Optional.of(cp));

        ResumeDecision decision = coordinator.resolveResumePath("sess-1", "coordinator", "user-1", ctx);

        assertTrue(decision instanceof ResumeDecision.AbortedStale);
        verify(chatMemoryWriter).scrubUnfulfilledToolCalls("sess-1");
        verify(agentStore).save(cp);
        assertEquals(CheckpointStatus.ARCHIVED, cp.getStatus());
        assertEquals(CheckpointResumeCoordinator.FAIL_REASON_STALE_DISCARD, cp.getFailReason());
        assertNull(cp.getPendingToolName());
        verify(auditService)
                .record(
                        eq("sess-1"),
                        eq("user-1"),
                        eq("coordinator"),
                        eq(SecurityEventType.CHECKPOINT_FAILED),
                        contains("Stale"));
    }

    // ── COMPLETED with role match ───────────────────────────────

    @Test
    void resolveResumePath_WhenCompletedWithRoleMatch_ArchivesAndReturnsResumedHistory() {
        var cp = checkpoint("sess-1", "coordinator", CheckpointStatus.COMPLETED);
        var steps = List.of(ReasoningStep.pending("", "t1", Map.of()), ReasoningStep.pending("", "t2", Map.of()));
        when(agentStore.findLatestNonArchivedCheckpoint("sess-1")).thenReturn(Optional.of(cp));
        when(agentStore.deserializeSteps(cp)).thenReturn(steps);

        ResumeDecision decision = coordinator.resolveResumePath("sess-1", "coordinator", "user-1", null);

        assertTrue(decision instanceof ResumeDecision.ResumedHistory);
        verify(agentStore).save(cp);
        assertEquals(CheckpointStatus.ARCHIVED, cp.getStatus());
    }

    @Test
    void resolveResumePath_WhenCompletedPrunesStepsBeyondCarryLimit() {
        var cp = checkpoint("sess-1", "coordinator", CheckpointStatus.COMPLETED);
        var steps = List.of(
                ReasoningStep.pending("", "t1", Map.of()), ReasoningStep.pending("", "t2", Map.of()),
                ReasoningStep.pending("", "t3", Map.of()), ReasoningStep.pending("", "t4", Map.of()),
                ReasoningStep.pending("", "t5", Map.of()), ReasoningStep.pending("", "t6", Map.of()),
                ReasoningStep.pending("", "t7", Map.of()), ReasoningStep.pending("", "t8", Map.of()),
                ReasoningStep.pending("", "t9", Map.of()), ReasoningStep.pending("", "t10", Map.of()),
                ReasoningStep.pending("", "t11", Map.of()), ReasoningStep.pending("", "t12", Map.of()));
        when(agentStore.findLatestNonArchivedCheckpoint("sess-1")).thenReturn(Optional.of(cp));
        when(agentStore.deserializeSteps(cp)).thenReturn(steps);

        ResumeDecision decision = coordinator.resolveResumePath("sess-1", "coordinator", "user-1", null);

        assertTrue(decision instanceof ResumeDecision.ResumedHistory);
        assertEquals(10, ((ResumeDecision.ResumedHistory) decision).history().size());
    }

    // ── Terminal mismatch ───────────────────────────────────────

    @Test
    void resolveResumePath_WhenNonResumableStatusWithRoleMismatch_ReturnsFreshStartWithoutArchive() {
        var cp = checkpoint("sess-1", "coder", CheckpointStatus.FAILED);
        when(agentStore.findLatestNonArchivedCheckpoint("sess-1")).thenReturn(Optional.of(cp));

        ResumeDecision decision = coordinator.resolveResumePath("sess-1", "coordinator", "user-1", null);

        assertTrue(decision instanceof ResumeDecision.FreshStart);
        // Role mismatch + non-resumable status → fast-path FreshStart, no archive
        verify(agentStore, never()).save(any());
    }
}
