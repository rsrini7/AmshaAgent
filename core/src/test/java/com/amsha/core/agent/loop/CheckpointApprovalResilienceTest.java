package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.util.Optional;

import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.state.StatefulAgentStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CheckpointApprovalResilienceTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    AgentSseService sseService;

    @Mock
    ObjectMapper objectMapper;

    @Mock
    Instance<CheckpointResumeHandler> resumeHandlerInstance;

    @InjectMocks
    CheckpointApprovalService service;

    @Test
    void approveAll_throwsNotFound_whenNoCheckpointExists() {
        String sid = "s1";
        when(agentStore.findLatestAwaitingApproval(sid)).thenReturn(Optional.empty());

        CheckpointResolutionException ex = assertThrows(CheckpointResolutionException.class, () -> {
            service.approveAll(sid, "test");
        });

        assertEquals(404, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("No pending checkpoint"));
    }

    @Test
    void approveTool_throwsNotFound_whenNoCheckpointExists() {
        String sid = "s2";
        when(agentStore.findLatestAwaitingApproval(sid)).thenReturn(Optional.empty());

        CheckpointResolutionException ex = assertThrows(CheckpointResolutionException.class, () -> {
            service.approveTool(sid, "tool1", "test");
        });

        assertEquals(404, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("No pending checkpoint"));
    }

    @Test
    void denyAll_throwsNotFound_whenNoCheckpointExists() {
        String sid = "s3";
        when(agentStore.findLatestAwaitingApproval(sid)).thenReturn(Optional.empty());

        CheckpointResolutionException ex = assertThrows(CheckpointResolutionException.class, () -> {
            service.denyAll(sid, "test");
        });

        assertEquals(404, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("No pending checkpoint"));
    }

    @Test
    void denyTool_throwsNotFound_whenNoCheckpointExists() {
        String sid = "s4";
        when(agentStore.findLatestAwaitingApproval(sid)).thenReturn(Optional.empty());

        CheckpointResolutionException ex = assertThrows(CheckpointResolutionException.class, () -> {
            service.denyTool(sid, "tool2", "test");
        });

        assertEquals(404, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("No pending checkpoint"));
    }
}
