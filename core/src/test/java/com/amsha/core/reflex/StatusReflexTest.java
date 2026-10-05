package com.amsha.core.reflex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.state.ActiveWork;
import com.amsha.core.state.ActivityPhase;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CompactionStorePort;
import com.amsha.persistence.api.CompactionSummaryDto;
import com.amsha.persistence.api.CronStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StatusReflexTest {

    @Mock
    StatefulAgentStore statefulAgentStore;

    @Mock
    CompactionStorePort compactionStorePort;

    @Mock
    CronStorePort cronStorePort;

    @Mock
    GovernanceStrategy governanceStrategy;

    @InjectMocks
    StatusReflex reflex;

    @BeforeEach
    void defaultStatusQueries() {
        lenient().when(statefulAgentStore.findByStatus(any())).thenReturn(List.of());
        // Exercise the real session-tree query so the :delegate: filtering in the
        // store contract is covered, not bypassed by a canned answer.
        lenient().doCallRealMethod().when(statefulAgentStore).findActiveCheckpointsInSessionTree(anyString());
        lenient().when(statefulAgentStore.getActiveWork(anyString())).thenReturn(Optional.empty());
    }

    private MessageContext context() {
        ChannelUser user = new ChannelUser("user1", "chat1", "User 1", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/status", "sess-1", Instant.now());
        return new MessageContext("chat1", user, msg);
    }

    @Test
    void matchesRecognizesSlashStatus() {
        assertTrue(reflex.matches("/status"));
        assertTrue(reflex.matches("  /STATUS  "));
        assertTrue(reflex.matches("/Status"));
        assertTrue(reflex.matches("/token"));
        assertTrue(reflex.matches("/tokens"));
        assertTrue(reflex.matches("/usage"));
        assertTrue(reflex.matches("/stats"));
        assertTrue(reflex.matches("/model"));
    }

    @Test
    void rejectsNonStatusInput() {
        assertFalse(reflex.matches("/halt"));
        assertFalse(reflex.matches("status"));
        assertFalse(reflex.matches(""));
        assertFalse(reflex.matches(null));
    }

    @Test
    void executeShowsNotStartedWhenNoCheckpoints() {
        when(governanceStrategy.getBudgetUsd()).thenReturn(10.0);
        when(governanceStrategy.getSessionCost("sess-1")).thenReturn(0.0);
        when(governanceStrategy.getSessionInputTokens("sess-1")).thenReturn(0);
        when(governanceStrategy.getSessionOutputTokens("sess-1")).thenReturn(0);
        when(compactionStorePort.findLatestBySessionId("sess-1")).thenReturn(Optional.empty());

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/status", context());

        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("NOT_STARTED"));
    }

    @Test
    void executeShowsInProgressStatus() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getStatus()).thenReturn(CheckpointStatus.IN_PROGRESS);
        when(statefulAgentStore.load(eq("sess-1"), anyString())).thenReturn(Optional.of(cp));

        when(governanceStrategy.getBudgetUsd()).thenReturn(10.0);
        when(governanceStrategy.getSessionCost("sess-1")).thenReturn(1.5);
        when(governanceStrategy.getSessionInputTokens("sess-1")).thenReturn(500);
        when(governanceStrategy.getSessionOutputTokens("sess-1")).thenReturn(200);
        when(compactionStorePort.findLatestBySessionId("sess-1")).thenReturn(Optional.empty());

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/status", context());

        assertTrue(result.commandResponse().get().contains("IN_PROGRESS"));
        assertTrue(result.commandResponse().get().contains("1.5"));
    }

    @Test
    void executeShowsCompactedMemoryWhenSummaryExists() {
        CompactionSummaryDto summary = new CompactionSummaryDto("sess-1", "summary", Instant.now(), 15, "user1");
        when(compactionStorePort.findLatestBySessionId("sess-1")).thenReturn(Optional.of(summary));

        when(governanceStrategy.getBudgetUsd()).thenReturn(5.0);
        when(governanceStrategy.getSessionCost("sess-1")).thenReturn(0.0);
        when(governanceStrategy.getSessionInputTokens("sess-1")).thenReturn(0);
        when(governanceStrategy.getSessionOutputTokens("sess-1")).thenReturn(0);

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/status", context());

        assertTrue(result.commandResponse().get().contains("Compacted"));
        assertTrue(result.commandResponse().get().contains("15"));
    }

    @Test
    void executeShowsInProgressWhenResumingEvenIfPreviousCheckpointCompleted() {
        AgentCheckpoint completedCp = mock(AgentCheckpoint.class);
        when(completedCp.getStatus()).thenReturn(CheckpointStatus.COMPLETED);
        when(statefulAgentStore.load(eq("sess-1"), anyString())).thenReturn(Optional.of(completedCp));
        when(statefulAgentStore.isBatchResuming("sess-1")).thenReturn(true);
        when(statefulAgentStore.getActiveGoal("sess-1")).thenReturn(Optional.of("Fix high memory leak under load"));

        when(governanceStrategy.getBudgetUsd()).thenReturn(10.0);
        when(governanceStrategy.getSessionCost("sess-1")).thenReturn(0.5);
        when(governanceStrategy.getSessionInputTokens("sess-1")).thenReturn(100);
        when(governanceStrategy.getSessionOutputTokens("sess-1")).thenReturn(50);
        when(compactionStorePort.findLatestBySessionId("sess-1")).thenReturn(Optional.empty());

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/status", context());

        assertTrue(result.commandResponse().isPresent());
        String response = result.commandResponse().get();
        assertTrue(response.contains("IN_PROGRESS"));
        assertTrue(response.contains("Fix high memory leak under load"));
        assertFalse(response.contains("*Status:* COMPLETED"));
    }

    @Test
    void executeIncludesActivePhaseWhenResuming() {
        when(statefulAgentStore.isBatchResuming("sess-1")).thenReturn(true);
        when(statefulAgentStore.getActiveWork("sess-1"))
                .thenReturn(
                        Optional.of(ActiveWork.of("Fix high memory leak under load", ActivityPhase.tool("read_file"))));

        when(governanceStrategy.getBudgetUsd()).thenReturn(10.0);
        when(governanceStrategy.getSessionCost("sess-1")).thenReturn(0.5);
        when(governanceStrategy.getSessionInputTokens("sess-1")).thenReturn(100);
        when(governanceStrategy.getSessionOutputTokens("sess-1")).thenReturn(50);
        when(compactionStorePort.findLatestBySessionId("sess-1")).thenReturn(Optional.empty());

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/status", context());

        assertTrue(result.commandResponse().isPresent());
        String response = result.commandResponse().get();
        assertTrue(response.contains("IN_PROGRESS"));
        assertTrue(response.contains("Fix high memory leak under load"));
        assertTrue(response.contains("running read_file"), "status should surface the live phase");
    }

    @Test
    void executeShowsAwaitingApprovalWhenDelegatedChildCheckpointPaused() {
        AgentCheckpoint rootCp = mock(AgentCheckpoint.class);
        when(rootCp.getStatus()).thenReturn(CheckpointStatus.COMPLETED);
        when(statefulAgentStore.load(eq("sess-1"), anyString())).thenReturn(Optional.of(rootCp));

        AgentCheckpoint delegatedCp = mock(AgentCheckpoint.class);
        when(delegatedCp.getId()).thenReturn(999L);
        when(delegatedCp.getSessionId()).thenReturn("sess-1:delegate:researcher:1");
        when(delegatedCp.getStatus()).thenReturn(CheckpointStatus.AWAITING_APPROVAL);
        when(statefulAgentStore.findByStatus(CheckpointStatus.AWAITING_APPROVAL))
                .thenReturn(List.of(delegatedCp));

        when(governanceStrategy.getBudgetUsd()).thenReturn(10.0);
        when(governanceStrategy.getSessionCost("sess-1")).thenReturn(0.5);
        when(governanceStrategy.getSessionInputTokens("sess-1")).thenReturn(100);
        when(governanceStrategy.getSessionOutputTokens("sess-1")).thenReturn(50);
        when(compactionStorePort.findLatestBySessionId("sess-1")).thenReturn(Optional.empty());

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/status", context());

        assertTrue(result.commandResponse().isPresent());
        String response = result.commandResponse().get();
        assertTrue(response.contains("AWAITING_APPROVAL"));
        assertFalse(response.contains("*Status:* COMPLETED"));
    }

    @Test
    void executeIgnoresApprovalFromSimilarButUnrelatedSession() {
        AgentCheckpoint completedCp = mock(AgentCheckpoint.class);
        lenient().when(completedCp.getId()).thenReturn(1L);
        lenient().when(completedCp.getSessionId()).thenReturn("sess-1");
        when(completedCp.getStatus()).thenReturn(CheckpointStatus.COMPLETED);
        when(statefulAgentStore.load(eq("sess-1"), anyString())).thenReturn(Optional.of(completedCp));

        AgentCheckpoint unrelatedCp = mock(AgentCheckpoint.class);
        lenient().when(unrelatedCp.getId()).thenReturn(2L);
        lenient().when(unrelatedCp.getSessionId()).thenReturn("sess-10:delegate:researcher:1");
        when(statefulAgentStore.findByStatus(CheckpointStatus.AWAITING_APPROVAL))
                .thenReturn(List.of(unrelatedCp));

        when(governanceStrategy.getBudgetUsd()).thenReturn(10.0);
        when(governanceStrategy.getSessionCost("sess-1")).thenReturn(0.0);
        when(governanceStrategy.getSessionInputTokens("sess-1")).thenReturn(0);
        when(governanceStrategy.getSessionOutputTokens("sess-1")).thenReturn(0);
        when(compactionStorePort.findLatestBySessionId("sess-1")).thenReturn(Optional.empty());

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/status", context());

        assertTrue(result.commandResponse().orElseThrow().contains("COMPLETED"));
        assertFalse(result.commandResponse().orElseThrow().contains("AWAITING_APPROVAL"));
    }

    @Test
    void executeReportsActiveDelegatedDescendantOverCompletedRoot() {
        AgentCheckpoint root = mock(AgentCheckpoint.class);
        when(root.getId()).thenReturn(1L);
        when(root.getStatus()).thenReturn(CheckpointStatus.COMPLETED);
        when(statefulAgentStore.load(eq("sess-1"), anyString())).thenReturn(Optional.of(root));

        AgentCheckpoint child = mock(AgentCheckpoint.class);
        when(child.getId()).thenReturn(3L);
        when(child.getSessionId()).thenReturn("sess-1:delegate:researcher:1");
        when(child.getStatus()).thenReturn(CheckpointStatus.IN_PROGRESS);
        when(child.getOriginalGoal()).thenReturn("Diagnose high memory usage under load");
        when(statefulAgentStore.findByStatus(CheckpointStatus.IN_PROGRESS)).thenReturn(List.of(child));

        when(governanceStrategy.getBudgetUsd()).thenReturn(10.0);
        when(governanceStrategy.getSessionCost("sess-1")).thenReturn(0.0);
        when(governanceStrategy.getSessionInputTokens("sess-1")).thenReturn(0);
        when(governanceStrategy.getSessionOutputTokens("sess-1")).thenReturn(0);
        when(compactionStorePort.findLatestBySessionId("sess-1")).thenReturn(Optional.empty());

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/status", context());

        String response = result.commandResponse().orElseThrow();
        assertTrue(response.contains("IN_PROGRESS"));
        assertTrue(response.contains("Diagnose high memory usage under load"));
        assertFalse(response.contains("*Status:* COMPLETED"));
    }

    @Test
    void executeIgnoresStaleInProgressCheckpointAndFallsBackToTerminal() {
        AgentCheckpoint staleInProgress = mock(AgentCheckpoint.class);
        when(staleInProgress.getId()).thenReturn(1L);
        when(staleInProgress.getStatus()).thenReturn(CheckpointStatus.IN_PROGRESS);
        // Stale timestamp (20 minutes ago, exceeds 15-minute staleness window)
        when(staleInProgress.getUpdatedAt()).thenReturn(Instant.now().minus(Duration.ofMinutes(20)));
        when(statefulAgentStore.load(eq("sess-1"), anyString())).thenReturn(Optional.of(staleInProgress));

        AgentCheckpoint priorCompleted = mock(AgentCheckpoint.class);
        when(priorCompleted.getId()).thenReturn(2L);
        when(priorCompleted.getStatus()).thenReturn(CheckpointStatus.COMPLETED);
        lenient().when(priorCompleted.getUpdatedAt()).thenReturn(Instant.now().minus(Duration.ofMinutes(25)));
        when(statefulAgentStore.findLatestNonArchivedCheckpoint("sess-1")).thenReturn(Optional.of(priorCompleted));

        when(governanceStrategy.getBudgetUsd()).thenReturn(10.0);
        when(governanceStrategy.getSessionCost("sess-1")).thenReturn(0.0);
        when(governanceStrategy.getSessionInputTokens("sess-1")).thenReturn(0);
        when(governanceStrategy.getSessionOutputTokens("sess-1")).thenReturn(0);
        when(compactionStorePort.findLatestBySessionId("sess-1")).thenReturn(Optional.empty());

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/status", context());

        String response = result.commandResponse().orElseThrow();
        assertTrue(response.contains("COMPLETED"), "stale IN_PROGRESS must not be returned in Priority 4");
        assertFalse(response.contains("IN_PROGRESS"));
    }

    @Test
    void statusShowsPendingScheduledRunLinkedToCompletedChatSession() {
        AgentCheckpoint completed = mock(AgentCheckpoint.class);
        lenient().when(completed.getId()).thenReturn(1L);
        lenient().when(completed.getSessionId()).thenReturn("sess-1");
        when(completed.getStatus()).thenReturn(CheckpointStatus.COMPLETED);
        when(statefulAgentStore.load(eq("sess-1"), anyString())).thenReturn(Optional.of(completed));
        when(cronStorePort.findByUserId("user1"))
                .thenReturn(List.of(new AgentCronDto(
                        "cron-1",
                        "sess-1",
                        "user1",
                        "chat1",
                        "WEBSOCKET",
                        "user1",
                        "40 14 5 10 *",
                        "Asia/Kolkata",
                        false,
                        "calculate factorial",
                        Instant.now(),
                        Instant.now(),
                        "AWAITING_APPROVAL",
                        "coder",
                        null,
                        "delivery-1",
                        Instant.now(),
                        Instant.now())));
        when(governanceStrategy.getBudgetUsd()).thenReturn(10.0);
        when(governanceStrategy.getSessionCost("sess-1")).thenReturn(0.0);
        when(governanceStrategy.getSessionInputTokens("sess-1")).thenReturn(0);
        when(governanceStrategy.getSessionOutputTokens("sess-1")).thenReturn(0);
        when(compactionStorePort.findLatestBySessionId("sess-1")).thenReturn(Optional.empty());

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/status", context());

        String response = result.commandResponse().orElseThrow();
        assertTrue(response.contains("*Status:* COMPLETED"));
        assertTrue(response.contains("COMPLETED (chat session)"));
        assertTrue(response.contains("Scheduled run: AWAITING_APPROVAL"));
        assertTrue(response.contains("Pending Approvals"));
    }
}
