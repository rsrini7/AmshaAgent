package com.arcclaw.app.ui;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;

import com.arcclaw.core.agent.hitl.CheckpointApprovalService;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.persistence.common.entity.AgentCheckpointEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class HitlServicePerToolApprovalTest {

    @Mock EntityManager em;
    @Mock CheckpointApprovalService checkpointApprovalService;
    @InjectMocks HitlService hitlService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void test_resolve_perToolApprove_delegatesCorrectly() {
        AgentCheckpointEntity cp = new AgentCheckpointEntity();
        cp.setSessionId("s1");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);

        when(em.find(AgentCheckpointEntity.class, 1L)).thenReturn(cp);

        hitlService.resolve("1", ToolCapability.GITHUB.toolName(), true, "approved github");

        verify(checkpointApprovalService).approveTool("s1", ToolCapability.GITHUB.toolName(), "approved github");
        verify(checkpointApprovalService, never()).approveAll(anyString(), anyString());
    }

    @Test
    void test_resolve_perToolDeny_delegatesCorrectly() {
        AgentCheckpointEntity cp = new AgentCheckpointEntity();
        cp.setSessionId("s1");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);

        when(em.find(AgentCheckpointEntity.class, 1L)).thenReturn(cp);

        hitlService.resolve("1", "sandboxexec", false, "unsafe");

        verify(checkpointApprovalService).denyTool("s1", "sandboxexec", "unsafe");
        verify(checkpointApprovalService, never()).denyAll(anyString(), anyString());
    }

    @Test
    void test_resolve_bulkApprove_stillWorks() {
        AgentCheckpointEntity cp = new AgentCheckpointEntity();
        cp.setSessionId("s1");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);

        when(em.find(AgentCheckpointEntity.class, 1L)).thenReturn(cp);

        hitlService.resolve("1", null, true, "bulk");

        verify(checkpointApprovalService).approveAll("s1", "bulk");
    }
}
