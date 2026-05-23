package com.arcclaw.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import jakarta.inject.Inject;

import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import io.quarkus.test.junit.QuarkusTest;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class HitlServiceTest {

    @Inject HitlService hitlService;
    @Inject SessionHeartbeatTracker heartbeatTracker;
    @Inject Jdbi jdbi;

    private Long cpUserA_Id;
    private Long cpUserB_Id;

    @BeforeEach
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

        jdbi.useHandle(handle -> {
            handle.createUpdate("DELETE FROM agent_checkpoint WHERE session_id IN ('session-userA', 'session-userB')").execute();

            cpUserA_Id = handle.createUpdate(
                "INSERT INTO agent_checkpoint (version, session_id, agent_role, status, pending_tool_name, updated_at, created_at, depth, iterations_used) " +
                "VALUES (0, 'session-userA', 'default', 'AWAITING_APPROVAL', 'toolA', :updatedAt, :createdAt, 0, 0)"
            )
            .bind("updatedAt", Instant.now())
            .bind("createdAt", Instant.now())
            .executeAndReturnGeneratedKeys("checkpoint_id")
            .mapTo(Long.class)
            .one();

            cpUserB_Id = handle.createUpdate(
                "INSERT INTO agent_checkpoint (version, session_id, agent_role, status, pending_tool_name, updated_at, created_at, depth, iterations_used) " +
                "VALUES (0, 'session-userB', 'default', 'AWAITING_APPROVAL', 'toolB', :updatedAt, :createdAt, 0, 0)"
            )
            .bind("updatedAt", Instant.now())
            .bind("createdAt", Instant.now())
            .executeAndReturnGeneratedKeys("checkpoint_id")
            .mapTo(Long.class)
            .one();
        });
    }

    @AfterEach
    public void teardown() {
        if (cpUserA_Id != null || cpUserB_Id != null) {
            jdbi.useHandle(handle -> {
                handle.createUpdate("DELETE FROM agent_checkpoint WHERE checkpoint_id IN (:idA, :idB)")
                    .bind("idA", cpUserA_Id != null ? cpUserA_Id : -1L)
                    .bind("idB", cpUserB_Id != null ? cpUserB_Id : -1L)
                    .execute();
            });
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
