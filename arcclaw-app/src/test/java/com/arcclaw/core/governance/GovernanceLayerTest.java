package com.arcclaw.core.governance;

import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class GovernanceLayerTest {

    @Inject
    GovernanceLayer governanceLayer;

    @Inject
    WorkspaceContext workspaceContext;

    private String sessionId;

    @BeforeEach
    void setup() {
        sessionId = UUID.randomUUID().toString();
        // Activate the workspace scope so AutonomyLevel modification can be verified
        WorkspaceScopeHolder.set(sessionId);
        // Default level is FULL or SUPERVISED based on config; ensure we know current
        workspaceContext.setAutonomyLevel(AutonomyLevel.FULL);
    }

    @AfterEach
    void tearDown() {
        governanceLayer.clearSession(sessionId);
        WorkspaceScopeHolder.clear();
    }

    @Test
    void testCostAccumulationUnderBudget() {
        // Price: In=$0.003/1k, Out=$0.015/1k
        // Let's record 10k input ($0.03) and 2k output ($0.03) -> Total: $0.06
        assertDoesNotThrow(() -> governanceLayer.recordLlmCall(sessionId, 10000, 2000));
        assertEquals(AutonomyLevel.FULL, workspaceContext.getAutonomyLevel(), "Autonomy should not be downgraded.");
    }

    @Test
    void testBudgetExceededTriggersDowngradeAndException() {
        // Budget is $10.00 in our application.yaml default.
        // Let's create a massive transaction to blow the budget.
        // 1 Million input tokens = $3.00
        // 1 Million output tokens = $15.00 -> Total $18.00 (exceeds $10)

        // Pre-condition
        assertEquals(AutonomyLevel.FULL, workspaceContext.getAutonomyLevel());

        BudgetExceededException ex = assertThrows(BudgetExceededException.class,
                () -> governanceLayer.recordLlmCall(sessionId, 1000000, 1000000));

        assertTrue(ex.getMessage().contains("exceeded"), "Exception should state budget was exceeded");

        // Key logic: Autonomy must be downgraded
        assertEquals(AutonomyLevel.READ_ONLY, workspaceContext.getAutonomyLevel(),
                "Autonomy level should be downgraded to READ_ONLY instantly");
    }

    @Test
    void testSessionIsolation() {
        String sessionB = UUID.randomUUID().toString();
        // Switch to sessionB context temporarily
        WorkspaceScopeHolder.set(sessionB);
        try {
            governanceLayer.recordLlmCall(sessionB, 1000000, 1000000); // Blows budget B
        } catch (BudgetExceededException e) {
            // expected
        } finally {
            // Switch back to original sessionId context
            WorkspaceScopeHolder.set(sessionId);
        }

        // We are querying `workspaceContext` for 'sessionId' (currently bound to
        // thread)
        // It should still be FULL because sessionB caused the failure
        assertEquals(AutonomyLevel.FULL, workspaceContext.getAutonomyLevel());
    }
}
