package com.arcclaw.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class HitlServiceTest {

    @Inject HitlService hitlService;
    @Inject SessionHeartbeatTracker heartbeatTracker;
    @Inject EntityManager em;

    private Long cpUserA_Id;
    private Long cpUserB_Id;

    @BeforeEach
    @Transactional
    public void setup() {
        heartbeatTracker.clearAll();

        // Session for userA
        String sessionA = "session-userA";
        heartbeatTracker.recordHeartbeat(sessionA);
        heartbeatTracker.tryBindOwner(sessionA, "userA");

        // Session for userB
        String sessionB = "session-userB";
        heartbeatTracker.recordHeartbeat(sessionB);
        heartbeatTracker.tryBindOwner(sessionB, "userB");

        // Checkpoint for userA
        AgentCheckpoint cpA = new AgentCheckpoint();
        cpA.setSessionId(sessionA);
        cpA.setAgentRole("default");
        cpA.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cpA.setPendingToolName("toolA");
        cpA.setUpdatedAt(Instant.now());
        em.persist(cpA);
        cpUserA_Id = cpA.getId();

        // Checkpoint for userB
        AgentCheckpoint cpB = new AgentCheckpoint();
        cpB.setSessionId(sessionB);
        cpB.setAgentRole("default");
        cpB.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cpB.setPendingToolName("toolB");
        cpB.setUpdatedAt(Instant.now());
        em.persist(cpB);
        cpUserB_Id = cpB.getId();
        
        em.flush();
    }

    @AfterEach
    @Transactional
    public void teardown() {
        if (cpUserA_Id != null) {
            AgentCheckpoint cp = em.find(AgentCheckpoint.class, cpUserA_Id);
            if (cp != null) em.remove(cp);
        }
        if (cpUserB_Id != null) {
            AgentCheckpoint cp = em.find(AgentCheckpoint.class, cpUserB_Id);
            if (cp != null) em.remove(cp);
        }
        heartbeatTracker.clearAll();
    }

    @Test
    public void testGetPendingApprovalsIsTenantScoped() {
        // userA should only see cpA
        Map<String, HitlService.PendingApproval> userAApprovals = hitlService.getPendingApprovals("userA", 50, 0);
        assertEquals(1, userAApprovals.size());
        assertTrue(userAApprovals.containsKey(String.valueOf(cpUserA_Id)));

        // userB should only see cpB
        Map<String, HitlService.PendingApproval> userBApprovals = hitlService.getPendingApprovals("userB", 50, 0);
        assertEquals(1, userBApprovals.size());
        assertTrue(userBApprovals.containsKey(String.valueOf(cpUserB_Id)));
        
        // Blank or null user yields empty results
        assertTrue(hitlService.getPendingApprovals(null, 50, 0).isEmpty());
        assertTrue(hitlService.getPendingApprovals("", 50, 0).isEmpty());
    }
}
