package com.arcclaw.core.security;

import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class WorkspaceGovernanceTest {

    @Inject
    WorkspaceContext context;

    private static final String TEST_SESSION_ID = "gov-test-session";

    @Test
    void testWorkspaceContextInjection() {
        WorkspaceScopeHolder.run(TEST_SESSION_ID, () -> {
            assertNotNull(context);
            assertEquals(TEST_SESSION_ID, context.getSessionId());
            assertEquals(AutonomyLevel.READONLY, context.getAutonomyLevel());
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

    @Test
    void testContextStatePersistence() {
        WorkspaceScopeHolder.run(TEST_SESSION_ID, () -> {
            context.setAutonomyLevel(AutonomyLevel.FULL);
            context.setAttribute("test-attr", "test-val");

            assertEquals(AutonomyLevel.FULL, context.getAutonomyLevel());
            assertEquals("test-val", context.getAttribute("test-attr"));
        });
    }
}
