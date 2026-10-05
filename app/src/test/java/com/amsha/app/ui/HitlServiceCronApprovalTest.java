package com.amsha.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.CronStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HitlServiceCronApprovalTest {

    private static final String USER_ID = "cron-owner";
    private static final String OTHER_USER_ID = "other-user";
    private static final String CRON_ID = "ec80745b-1d87-4612-9b88-0cb91d6543f6";
    private static final String DELIVERY_ID = "74d6f3c1-56f1-44df-9058-f00c785570ac";
    private static final String SESSION_ID = "cron-" + CRON_ID + "-" + DELIVERY_ID + ":delegate:brahma:1";

    @Mock
    CheckpointStorePort checkpointStorePort;

    @Mock
    CronStorePort cronStorePort;

    @Mock
    CheckpointApprovalService checkpointApprovalService;

    @Mock
    AgentOtelContext otelContext;

    @Mock
    SessionHeartbeatTracker heartbeatTracker;

    private HitlService service;
    private CheckpointDto checkpoint;

    @BeforeEach
    void setUp() {
        service = new HitlService();
        service.checkpointStorePort = checkpointStorePort;
        service.cronStorePort = cronStorePort;
        service.checkpointApprovalService = checkpointApprovalService;
        service.otelContext = otelContext;
        service.heartbeatTracker = heartbeatTracker;
        service.objectMapper = new ObjectMapper();
        checkpoint = new CheckpointDto(
                12L,
                SESSION_ID,
                "coder",
                CheckpointStatus.AWAITING_APPROVAL.name(),
                null,
                null,
                null,
                null,
                "run factorial",
                null,
                null,
                null,
                "sandbox_exec",
                null,
                "{\"code\":\"print(14)\",\"language\":\"python\"}",
                null,
                10,
                2,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                2,
                Instant.now(),
                Instant.now());
        when(cronStorePort.findById(CRON_ID)).thenReturn(Optional.of(cron(USER_ID)));
    }

    @Test
    void listsCronApprovalOnlyForOwningUser() {
        when(heartbeatTracker.getSessionIdsByOwner(USER_ID)).thenReturn(List.of());
        when(heartbeatTracker.getSessionIdsByOwner(OTHER_USER_ID)).thenReturn(List.of("session-other"));
        when(heartbeatTracker.getUnownedSessionIds()).thenReturn(List.of());
        when(checkpointStorePort.findByStatus(CheckpointStatus.AWAITING_APPROVAL.name()))
                .thenReturn(List.of(checkpoint));
        when(checkpointStorePort.findByStatusAndSessionIds(
                        eq(CheckpointStatus.AWAITING_APPROVAL.name()), anyList(), eq(50), eq(0)))
                .thenAnswer(invocation -> {
                    List<String> sessionIds = invocation.getArgument(1);
                    return sessionIds.contains(SESSION_ID) ? List.of(checkpoint) : List.of();
                });
        when(checkpointStorePort.findActiveBySessionTree(SESSION_ID)).thenReturn(List.of(checkpoint));
        when(checkpointStorePort.findActiveBySessionTree("session-other")).thenReturn(List.of(checkpoint));

        Map<String, HitlService.PendingApproval> ownerApprovals = service.getPendingApprovals(USER_ID, 50, 0);
        Map<String, HitlService.PendingApproval> otherApprovals = service.getPendingApprovals(OTHER_USER_ID, 50, 0);

        assertEquals(1, ownerApprovals.size());
        assertEquals(SESSION_ID, ownerApprovals.get("12").sessionId());
        assertEquals("print(14)", ownerApprovals.get("12").params().get("code"));
        assertEquals(Map.of(), otherApprovals);
    }

    @Test
    void allowsOnlyCronOwnerToResolveApprovalFromPanel() {
        when(checkpointStorePort.findById(12L)).thenReturn(Optional.of(checkpoint));

        assertThrows(
                SecurityException.class, () -> service.resolve("12", "sandbox_exec", true, "approved", OTHER_USER_ID));
        verify(checkpointApprovalService, never()).approveTool(SESSION_ID, "sandbox_exec", "approved");
        service.resolve("12", "sandbox_exec", true, "approved", USER_ID);
        verify(checkpointApprovalService).approveTool(SESSION_ID, "sandbox_exec", "approved");
    }

    private static AgentCronDto cron(String owner) {
        Instant now = Instant.now();
        return new AgentCronDto(
                CRON_ID,
                "origin-session",
                owner,
                "chat-1",
                "WEBSOCKET",
                owner,
                "40 14 5 10 *",
                "Asia/Kolkata",
                false,
                "run factorial",
                now,
                null,
                "AWAITING_APPROVAL",
                null,
                null,
                DELIVERY_ID,
                now,
                now);
    }
}
