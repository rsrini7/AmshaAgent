package com.amsha.app.ui;

import static org.mockito.Mockito.*;

import java.util.Optional;

import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class HitlServiceAbstractionTest {

    @Mock
    CheckpointStorePort checkpointStorePort;

    @Mock
    CheckpointApprovalService checkpointApprovalService;

    @Mock
    AgentOtelContext otelContext;

    @Mock
    ObjectMapper objectMapper;

    @Mock
    SessionHeartbeatTracker heartbeatTracker;

    @InjectMocks
    HitlService hitlService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(heartbeatTracker.getOwner(anyString())).thenReturn(Optional.of("test-user"));
    }

    @Test
    void test_resolve_approve_callsApproveAllNotReflex() {
        CheckpointDto cp = new CheckpointDto(
                1L,
                "s1",
                "default",
                CheckpointStatus.AWAITING_APPROVAL.name(),
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

        when(checkpointStorePort.findById(1L)).thenReturn(Optional.of(cp));

        hitlService.resolve("1", null, true, "reason", "test-user");

        verify(checkpointApprovalService).approveAll("s1", "reason");
    }

    @Test
    void test_resolve_deny_callsDenyAllNotReflex() {
        CheckpointDto cp = new CheckpointDto(
                1L,
                "s1",
                "default",
                CheckpointStatus.AWAITING_APPROVAL.name(),
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

        when(checkpointStorePort.findById(1L)).thenReturn(Optional.of(cp));

        hitlService.resolve("1", null, false, "reason", "test-user");

        verify(checkpointApprovalService).denyAll("s1", "reason");
    }
}
