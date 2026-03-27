package com.arcclaw.core.lifecycle;

import static java.time.temporal.ChronoUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.hibernate.query.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class WorkspaceManagerGcSweepTest {

    @Inject
    SessionReaper sessionReaper;

    @InjectMock
    WorkspaceManager workspaceManager;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @InjectMock
    EntityManager emMock;

    @InjectMock
    StatefulAgentStore agentStoreMock; // Mock to avoid DB hits during irrelevant side effects

    @BeforeEach
    void setup() {
        heartbeatTracker.clearAll();
    }

    @Test
    void testGcSweep_awaitingApprovalSession_isNotTerminated() {
        String sessionId = "session-awaiting";
        
        heartbeatTracker.injectHeartbeat(sessionId, Instant.now().minus(60, MINUTES));

        // Stub EntityManager
        stubEntityManager(sessionId, true, List.of());

        // Act
        sessionReaper.gcSweep();

        // Assert: session still exists (not terminated)
        assertEquals(1, heartbeatTracker.activeSessionCount());
        verify(workspaceManager, never()).terminateSession(anyString());
    }

    @Test
    void testGcSweep_expiredNonAwaitingSession_isTerminated() {
        String sessionId = "session-expired";
        
        heartbeatTracker.injectHeartbeat(sessionId, Instant.now().minus(60, MINUTES));

        // Stub EntityManager
        stubEntityManager(sessionId, false, List.of());

        // Act
        sessionReaper.gcSweep();

        // Assert: session is removed from heartbeats
        assertEquals(0, heartbeatTracker.activeSessionCount());
        verify(workspaceManager).terminateSession(sessionId);
    }

    @Test
    void testGcSweep_bulkQuery_calledOnceFor3ExpiredSessions() {
        String s1 = "s1", s2 = "s2", s3 = "s3";
        
        heartbeatTracker.injectHeartbeat(s1, Instant.now().minus(60, MINUTES));
        heartbeatTracker.injectHeartbeat(s2, Instant.now().minus(60, MINUTES));
        heartbeatTracker.injectHeartbeat(s3, Instant.now().minus(60, MINUTES));

        // Stub EntityManager for s1 awaiting approval
        stubEntityManager(s1, true, List.of());

        // Act
        sessionReaper.gcSweep();

        // Assert: Bulk query executed exactly ONCE
        verify(emMock, times(1)).createQuery(contains("SELECT c.sessionId FROM AgentCheckpoint"), eq(String.class));

        // Assert: s1 survives, s2 and s3 are terminated
        assertEquals(1, heartbeatTracker.activeSessionCount());
        verify(workspaceManager, times(2)).terminateSession(anyString());
    }

    @Test
    void testSweepTimedOutApprovals_terminatesExpiredApprovalSession() {
        String sessionId = "timed-out-approval";
        
        heartbeatTracker.recordHeartbeat(sessionId);
        assertEquals(1, heartbeatTracker.activeSessionCount());

        // Setup mock to return a timed out checkpoint
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId(sessionId);
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);

        when(agentStoreMock.findStaleAwaitingApproval(any())).thenReturn(List.of(cp));

        // Act
        sessionReaper.sweepTimedOutApprovals();

        // Assert
        verify(workspaceManager).terminateSession(sessionId);
        assertEquals(0, heartbeatTracker.activeSessionCount(), "Heartbeat should be removed after termination sweep");
    }

    /**
     * Unified helper to stub EntityManager.
     */
    @SuppressWarnings("unchecked")
    private void stubEntityManager(String awaitingSessionId, boolean isAwaitingInBulk, List<AgentCheckpoint> timedOutList) {
        // 1. Bulk Query Mock
        TypedQuery<String> bulkQueryMock = mock(TypedQuery.class, withSettings().extraInterfaces(Query.class));
        when(bulkQueryMock.setParameter(anyString(), any())).thenReturn(bulkQueryMock);
        if (isAwaitingInBulk) {
            when(bulkQueryMock.getResultStream()).thenReturn(Stream.of(awaitingSessionId));
        } else {
            when(bulkQueryMock.getResultStream()).thenReturn(Stream.empty());
        }

        // 2. Timed Out Approvals Mock
        TypedQuery<AgentCheckpoint> timedOutQueryMock = mock(TypedQuery.class, withSettings().extraInterfaces(Query.class));
        when(timedOutQueryMock.setParameter(anyString(), any())).thenReturn(timedOutQueryMock);
        when(timedOutQueryMock.getResultList()).thenReturn(timedOutList);

        // 3. Fallback Mock
        TypedQuery<Object> fallbackQueryMock = mock(TypedQuery.class, withSettings().extraInterfaces(Query.class));
        when(fallbackQueryMock.setParameter(anyString(), any())).thenReturn(fallbackQueryMock);
        when(fallbackQueryMock.getResultList()).thenReturn(List.of());

        // Router
        doAnswer(invocation -> {
            String ql = invocation.getArgument(0);
            Class<?> clazz = invocation.getArgument(1);
            if (clazz == String.class && ql.contains("sessionId")) {
                return bulkQueryMock;
            } else if (clazz == AgentCheckpoint.class) {
                return timedOutQueryMock;
            }
            return fallbackQueryMock;
        }).when(emMock).createQuery(anyString(), any(Class.class));
    }
}
