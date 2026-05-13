package com.arcclaw.app.ui;

import static org.mockito.Mockito.*;

import jakarta.persistence.EntityManager;

import com.arcclaw.core.agent.hitl.CheckpointApprovalService;
import com.arcclaw.core.state.AgentCheckpointEntity;
import com.arcclaw.core.state.CheckpointStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class HitlServiceAbstractionTest {

    @Mock EntityManager em;
    @Mock CheckpointApprovalService checkpointApprovalService;
    @InjectMocks HitlService hitlService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void test_resolve_approve_callsApproveAllNotReflex() {
        AgentCheckpointEntity cp = new AgentCheckpointEntity();
        cp.setSessionId("s1");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);

        when(em.find(AgentCheckpointEntity.class, 1L)).thenReturn(cp);

        hitlService.resolve("1", null, true, "reason");

        verify(checkpointApprovalService).approveAll("s1", "reason");
        // Implicit verification: no Reflex interaction since they are out of the class.
    }

    @Test
    void test_resolve_deny_callsDenyAllNotReflex() {
        AgentCheckpointEntity cp = new AgentCheckpointEntity();
        cp.setSessionId("s1");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);

        when(em.find(AgentCheckpointEntity.class, 1L)).thenReturn(cp);

        hitlService.resolve("1", null, false, "reason");

        verify(checkpointApprovalService).denyAll("s1", "reason");
    }
}
