package com.amsha.app.ui;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Optional;

import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.agent.role.ToolCapability;
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

class HitlServicePerToolApprovalTest {

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
    }

    @Test
    void test_resolve_perToolApprove_delegatesCorrectly() {
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

        hitlService.resolve("1", ToolCapability.GITHUB.toolName(), true, "approved github");

        verify(checkpointApprovalService).approveTool("s1", ToolCapability.GITHUB.toolName(), "approved github");
        verify(checkpointApprovalService, never()).approveAll(anyString(), anyString());
    }

    @Test
    void test_resolve_perToolDeny_delegatesCorrectly() {
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

        hitlService.resolve("1", "sandboxexec", false, "unsafe");

        verify(checkpointApprovalService).denyTool("s1", "sandboxexec", "unsafe");
        verify(checkpointApprovalService, never()).denyAll(anyString(), anyString());
    }

    @Test
    void test_resolve_bulkApprove_stillWorks() {
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

        hitlService.resolve("1", null, true, "bulk");

        verify(checkpointApprovalService).approveAll("s1", "bulk");
    }
}
