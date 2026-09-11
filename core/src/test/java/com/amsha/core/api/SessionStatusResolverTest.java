package com.amsha.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.amsha.core.api.dto.ServiceResult;
import com.amsha.core.api.dto.SessionStatusResponse;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionStatusResolverTest {

    @Mock
    StatefulAgentStore agentStore;

    private static final String ROOT_SESSION = "sess-root";
    private static final String ROLE = "coder";

    @BeforeEach
    void setUp() {
        when(agentStore.getActiveWork(anyString())).thenReturn(Optional.empty());
        when(agentStore.getActiveGoal(anyString())).thenReturn(Optional.empty());
        when(agentStore.getActivePhase(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void resolvesDelegatedAwaitingApprovalWhenExactSessionHasNone() {
        when(agentStore.findLatestAwaitingApproval(ROOT_SESSION)).thenReturn(Optional.empty());

        AgentCheckpoint delegatedApproval = mock(AgentCheckpoint.class);
        when(delegatedApproval.getSessionId()).thenReturn(ROOT_SESSION + ":delegate:sub-1");
        when(delegatedApproval.getStatus()).thenReturn(CheckpointStatus.AWAITING_APPROVAL);
        when(delegatedApproval.getPendingToolName()).thenReturn("web_fetch");
        when(agentStore.findLatestAwaitingApprovalBySessionContaining(ROOT_SESSION + ":delegate:"))
                .thenReturn(Optional.of(delegatedApproval));

        ServiceResult<Object> result = SessionStatusResolver.resolve(agentStore, ROOT_SESSION, ROLE, null);

        assertTrue(result.isSuccess());
        SessionStatusResponse resp = (SessionStatusResponse) result.value();
        assertEquals(ROOT_SESSION, resp.sessionId());
        assertEquals("AWAITING_APPROVAL", resp.status());
    }

    @Test
    void delegatedActiveResumingCheckpointReportsLiveInProgressWhenNotStale() {
        AgentCheckpoint completedRoot = mock(AgentCheckpoint.class);
        when(completedRoot.getSessionId()).thenReturn(ROOT_SESSION);
        when(completedRoot.getStatus()).thenReturn(CheckpointStatus.COMPLETED);
        when(agentStore.findLatestAwaitingApproval(ROOT_SESSION)).thenReturn(Optional.empty());
        when(agentStore.findLatestAwaitingApprovalBySessionContaining(ROOT_SESSION + ":delegate:"))
                .thenReturn(Optional.empty());
        when(agentStore.load(ROOT_SESSION, ROLE)).thenReturn(Optional.of(completedRoot));
        when(agentStore.isBatchResuming(ROOT_SESSION)).thenReturn(false);

        // Delegated checkpoint updated 2 minutes ago (non-stale)
        AgentCheckpoint delegatedActive = mock(AgentCheckpoint.class);
        when(delegatedActive.getStatus()).thenReturn(CheckpointStatus.RESUMING);
        when(delegatedActive.getUpdatedAt()).thenReturn(Instant.now().minus(2, ChronoUnit.MINUTES));
        when(agentStore.findLatestActiveDelegatedCheckpoint(ROOT_SESSION)).thenReturn(Optional.of(delegatedActive));

        ServiceResult<Object> result = SessionStatusResolver.resolve(agentStore, ROOT_SESSION, ROLE, null);

        assertTrue(result.isSuccess());
        SessionStatusResponse resp = (SessionStatusResponse) result.value();
        assertEquals("IN_PROGRESS", resp.status());
    }

    @Test
    void delegatedStaleResumingCheckpointFallsBackToCompleted() {
        AgentCheckpoint completedRoot = mock(AgentCheckpoint.class);
        when(completedRoot.getSessionId()).thenReturn(ROOT_SESSION);
        when(completedRoot.getStatus()).thenReturn(CheckpointStatus.COMPLETED);
        when(agentStore.findLatestAwaitingApproval(ROOT_SESSION)).thenReturn(Optional.empty());
        when(agentStore.findLatestAwaitingApprovalBySessionContaining(ROOT_SESSION + ":delegate:"))
                .thenReturn(Optional.empty());
        when(agentStore.load(ROOT_SESSION, ROLE)).thenReturn(Optional.of(completedRoot));
        when(agentStore.isBatchResuming(ROOT_SESSION)).thenReturn(false);

        // Delegated checkpoint updated 6 minutes ago (stale for RESUMING, threshold is 5 mins)
        AgentCheckpoint delegatedStale = mock(AgentCheckpoint.class);
        when(delegatedStale.getStatus()).thenReturn(CheckpointStatus.RESUMING);
        when(delegatedStale.getUpdatedAt()).thenReturn(Instant.now().minus(6, ChronoUnit.MINUTES));
        when(agentStore.findLatestActiveDelegatedCheckpoint(ROOT_SESSION)).thenReturn(Optional.of(delegatedStale));

        ServiceResult<Object> result = SessionStatusResolver.resolve(agentStore, ROOT_SESSION, ROLE, null);

        assertTrue(result.isSuccess());
        SessionStatusResponse resp = (SessionStatusResponse) result.value();
        assertEquals("COMPLETED", resp.status());
    }

    @Test
    void delegatedStaleInProgressCheckpointFallsBackToCompleted() {
        AgentCheckpoint completedRoot = mock(AgentCheckpoint.class);
        when(completedRoot.getSessionId()).thenReturn(ROOT_SESSION);
        when(completedRoot.getStatus()).thenReturn(CheckpointStatus.COMPLETED);
        when(agentStore.findLatestAwaitingApproval(ROOT_SESSION)).thenReturn(Optional.empty());
        when(agentStore.findLatestAwaitingApprovalBySessionContaining(ROOT_SESSION + ":delegate:"))
                .thenReturn(Optional.empty());
        when(agentStore.load(ROOT_SESSION, ROLE)).thenReturn(Optional.of(completedRoot));
        when(agentStore.isBatchResuming(ROOT_SESSION)).thenReturn(false);

        // Delegated checkpoint updated 16 minutes ago (stale for IN_PROGRESS, threshold is 15 mins)
        AgentCheckpoint delegatedStale = mock(AgentCheckpoint.class);
        when(delegatedStale.getStatus()).thenReturn(CheckpointStatus.IN_PROGRESS);
        when(delegatedStale.getUpdatedAt()).thenReturn(Instant.now().minus(16, ChronoUnit.MINUTES));
        when(agentStore.findLatestActiveDelegatedCheckpoint(ROOT_SESSION)).thenReturn(Optional.of(delegatedStale));

        ServiceResult<Object> result = SessionStatusResolver.resolve(agentStore, ROOT_SESSION, ROLE, null);

        assertTrue(result.isSuccess());
        SessionStatusResponse resp = (SessionStatusResponse) result.value();
        assertEquals("COMPLETED", resp.status());
    }

    @Test
    void rootApprovalForDifferentRoleDoesNotHijackRequestedRole() {
        AgentCheckpoint completedCoder = mock(AgentCheckpoint.class);
        when(completedCoder.getSessionId()).thenReturn(ROOT_SESSION);
        when(completedCoder.getStatus()).thenReturn(CheckpointStatus.COMPLETED);
        when(completedCoder.getAgentRole()).thenReturn(ROLE);
        when(agentStore.load(ROOT_SESSION, ROLE)).thenReturn(Optional.of(completedCoder));

        when(agentStore.findLatestAwaitingApprovalBySessionContaining(ROOT_SESSION + ":delegate:"))
                .thenReturn(Optional.empty());

        ServiceResult<Object> result = SessionStatusResolver.resolve(agentStore, ROOT_SESSION, ROLE, null);

        assertTrue(result.isSuccess());
        SessionStatusResponse resp = (SessionStatusResponse) result.value();
        assertEquals(ROOT_SESSION, resp.sessionId());
        assertEquals("COMPLETED", resp.status());
    }

    @Test
    void rootApprovalForRequestedRoleIsPreserved() {
        AgentCheckpoint awaitingCoder = mock(AgentCheckpoint.class);
        when(awaitingCoder.getSessionId()).thenReturn(ROOT_SESSION);
        when(awaitingCoder.getStatus()).thenReturn(CheckpointStatus.AWAITING_APPROVAL);
        when(awaitingCoder.getPendingToolName()).thenReturn("edit_file");
        when(agentStore.load(ROOT_SESSION, ROLE)).thenReturn(Optional.of(awaitingCoder));

        ServiceResult<Object> result = SessionStatusResolver.resolve(agentStore, ROOT_SESSION, ROLE, null);

        assertTrue(result.isSuccess());
        SessionStatusResponse resp = (SessionStatusResponse) result.value();
        assertEquals(ROOT_SESSION, resp.sessionId());
        assertEquals("AWAITING_APPROVAL", resp.status());
    }
}
