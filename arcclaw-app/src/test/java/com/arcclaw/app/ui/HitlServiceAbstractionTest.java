package com.arcclaw.app.ui;

import static org.mockito.Mockito.*;

import jakarta.persistence.EntityManager;

import com.arcclaw.core.agent.loop.CheckpointApprovalService;
import com.arcclaw.core.state.AgentCheckpoint;
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
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("s1");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);

        when(em.find(AgentCheckpoint.class, 1L)).thenReturn(cp);

        hitlService.resolve("1", null, true, "reason");

        verify(checkpointApprovalService).approveAll("s1", "reason");
        // Implicit verification: no Reflex interaction since they are out of the class.
    }

    @Test
    void test_resolve_deny_callsDenyAllNotReflex() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("s1");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);

        when(em.find(AgentCheckpoint.class, 1L)).thenReturn(cp);

        hitlService.resolve("1", null, false, "reason");

        verify(checkpointApprovalService).denyAll("s1", "reason");
    }
}
