package com.arcclaw.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arcclaw.core.scope.WorkspaceContext;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class WorkspaceGovernanceTest {


    private static final String TEST_SESSION_ID = "gov-test-session";

    @Test
    void testSessionIdBoundCorrectly() {
        WorkspaceContext.run(TEST_SESSION_ID, "gov-user", "gov-chat", AutonomyLevel.READONLY, "coordinator", () -> {
            assertEquals(TEST_SESSION_ID, WorkspaceContext.requireSessionId());
        });
    }

    @Test
    void testAutonomyBoundCorrectly() {
        WorkspaceContext.run(TEST_SESSION_ID, "gov-user", "gov-chat", AutonomyLevel.SUPERVISED, "coordinator", () -> {
            assertEquals(AutonomyLevel.SUPERVISED, WorkspaceContext.autonomy());
        });
    }

    @Test
    void testAutonomyPolicyLogic() {
        AutonomyPolicy policy = new AutonomyPolicy.Default();

        // READONLY
        assertTrue(policy.isAllowed(AutonomyLevel.READONLY, ToolOperation.READ_FS));
        assertFalse(policy.isAllowed(AutonomyLevel.READONLY, ToolOperation.WRITE_FS));

        // FULL
        assertTrue(policy.isAllowed(AutonomyLevel.FULL, ToolOperation.EXECUTE_SHELL));

        // SUPERVISED
        assertTrue(policy.isAllowed(AutonomyLevel.SUPERVISED, ToolOperation.READ_FS));
        assertFalse(policy.isAllowed(AutonomyLevel.SUPERVISED, ToolOperation.VAULT_ACCESS));
    }


}
