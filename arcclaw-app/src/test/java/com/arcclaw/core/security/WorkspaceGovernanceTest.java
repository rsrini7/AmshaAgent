package com.arcclaw.core.security;

import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class WorkspaceGovernanceTest {

    @Inject
    WorkspaceContext context;

    private static final String TEST_SESSION_ID = "gov-test-session";

    @BeforeEach
    void setup() {
        WorkspaceScopeHolder.set(TEST_SESSION_ID);
    }

    @AfterEach
    void tearDown() {
        WorkspaceScopeHolder.clear();
    }

    @Test
    void testWorkspaceContextInjection() {
        assertNotNull(context);
        assertEquals(TEST_SESSION_ID, context.getSessionId());
        assertEquals(AutonomyLevel.READ_ONLY, context.getAutonomyLevel());
    }

    @Test
    void testAutonomyPolicyLogic() {
        AutonomyPolicy policy = new AutonomyPolicy.Default();

        // READ_ONLY
        assertTrue(policy.isAllowed(AutonomyLevel.READ_ONLY, ToolOperation.READ_FS));
        assertFalse(policy.isAllowed(AutonomyLevel.READ_ONLY, ToolOperation.WRITE_FS));

        // FULL
        assertTrue(policy.isAllowed(AutonomyLevel.FULL, ToolOperation.EXECUTE_SHELL));

        // PARTIAL
        assertTrue(policy.isAllowed(AutonomyLevel.PARTIAL, ToolOperation.READ_FS));
        assertFalse(policy.isAllowed(AutonomyLevel.PARTIAL, ToolOperation.VAULT_ACCESS));
    }

    @Test
    void testContextStatePersistence() {
        context.setAutonomyLevel(AutonomyLevel.FULL);
        context.setAttribute("test-attr", "test-val");

        assertEquals(AutonomyLevel.FULL, context.getAutonomyLevel());
        assertEquals("test-val", context.getAttribute("test-attr"));
    }
}
