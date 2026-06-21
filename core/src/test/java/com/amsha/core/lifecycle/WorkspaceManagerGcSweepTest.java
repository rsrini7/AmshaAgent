package com.amsha.core.lifecycle;

import static java.time.temporal.ChronoUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import com.amsha.core.audit.AuditHashUtil;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class WorkspaceManagerGcSweepTest {

    @Inject
    SessionReaper sessionReaper;

    @InjectMock
    WorkspaceManager workspaceManager;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @InjectMock
    CheckpointStorePort checkpointStorePortMock; // Mock new port instead of EM

    @InjectMock
    StatefulAgentStore agentStoreMock; // Mock to avoid DB hits during irrelevant side effects

    @InjectMock
    AuditHashUtil auditHashUtil;

    @BeforeEach
    void setup() {
        heartbeatTracker.clearAll();
        lenient().when(auditHashUtil.sha256hex(anyString())).thenCallRealMethod();
        lenient().when(auditHashUtil.hmacHex(anyString())).thenAnswer(inv -> {
            String data = inv.getArgument(0);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
        });
        lenient().doCallRealMethod().when(auditHashUtil).calculateChainFields(any(), any());
    }

    @Test
    void testGcSweep_awaitingApprovalSession_isNotTerminated() {
        String sessionId = "session-awaiting";

        heartbeatTracker.injectHeartbeat(sessionId, Instant.now().minus(60, MINUTES));

        // Stub CheckpointStorePort
        stubCheckpointStorePort(sessionId, true);

        // Act
        sessionReaper.gcSweep();

        // Assert: session still exists (not terminated)
        assertEquals(1, heartbeatTracker.activeSessionCount());
        verify(workspaceManager, never()).terminateSession(anyString());
    }

    @Test
    void testGcSweep_resumingSession_isNotTerminated() {
        String sessionId = "session-resuming";

        heartbeatTracker.injectHeartbeat(sessionId, Instant.now().minus(60, MINUTES));

        // Stub CheckpointStorePort
        stubCheckpointStorePort(sessionId, false);

        // Mock session as resuming
        when(agentStoreMock.isBatchResuming(sessionId)).thenReturn(true);

        // Act
        sessionReaper.gcSweep();

        // Assert: session still exists (not terminated)
        assertEquals(1, heartbeatTracker.activeSessionCount(), "Resuming session should be preserved");
        verify(workspaceManager, never()).terminateSession(anyString());
    }

    @Test
    void testGcSweep_expiredNonAwaitingSession_isTerminated() {
        String sessionId = "session-expired";

        heartbeatTracker.injectHeartbeat(sessionId, Instant.now().minus(60, MINUTES));

        // Stub CheckpointStorePort
        stubCheckpointStorePort(sessionId, false);

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

        // Stub CheckpointStorePort for s1 awaiting approval
        stubCheckpointStorePort(s1, true);

        // Act
        sessionReaper.gcSweep();

        // Assert: Bulk query executed exactly ONCE
        verify(checkpointStorePortMock, times(1)).findByStatus(CheckpointStatus.AWAITING_APPROVAL.name());

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
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId(sessionId);
        cp.setAgentRole("coordinator");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);

        when(agentStoreMock.findStaleAwaitingApproval(any())).thenReturn(List.of(cp));

        // Act
        sessionReaper.sweepTimedOutApprovals();

        // Assert
        verify(workspaceManager).terminateSession(sessionId);
        assertEquals(0, heartbeatTracker.activeSessionCount(), "Heartbeat should be removed after termination sweep");
    }

    /**
     * Unified helper to stub CheckpointStorePort.
     */
    private void stubCheckpointStorePort(String awaitingSessionId, boolean isAwaitingInBulk) {
        if (isAwaitingInBulk) {
            CheckpointDto dto = new CheckpointDto(
                    1L,
                    awaitingSessionId,
                    "agent",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    null,
                    null);
            when(checkpointStorePortMock.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name()))
                    .thenReturn(List.of(dto));
        } else {
            when(checkpointStorePortMock.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name()))
                    .thenReturn(List.of());
        }
    }
}
