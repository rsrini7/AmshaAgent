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
        // Let's record 1k input ($0.003) and 1k output ($0.015) -> Total: $0.018
        assertDoesNotThrow(() -> governanceLayer.recordLlmCall(sessionId, 1000, 1000));
        assertEquals(AutonomyLevel.FULL, workspaceContext.getAutonomyLevel(), "Autonomy should not be downgraded.");
        
        assertEquals(0.018, governanceLayer.getSessionCost(sessionId), 0.0001);
        assertEquals(1000, governanceLayer.getSessionInputTokens(sessionId));
        assertEquals(1000, governanceLayer.getSessionOutputTokens(sessionId));
    }

    @Test
    void testBudgetWarningAt80Percent() {
        // Budget is $10.00. 80% is $8.00.
        // Record enough to hit $8.00 but not $10.00.
        // 100,000 input tokens = $0.30
        // 500,000 output tokens = $7.50 -> Total $7.80 (Under 80%)
        assertDoesNotThrow(() -> governanceLayer.recordLlmCall(sessionId, 100000, 500000));
        
        // Next call hits warning threshold.
        // Add $0.30 more -> Total $8.10 (> $8.00)
        BudgetWarningException ex = assertThrows(BudgetWarningException.class, 
            () -> governanceLayer.recordLlmCall(sessionId, 100000, 0));
        
        assertTrue(ex.getMessage().contains("budget is 81% consumed"));
        assertEquals(AutonomyLevel.FULL, workspaceContext.getAutonomyLevel(), "Warning should NOT downgrade autonomy.");
    }

    @Test
    void testBudgetExceededTriggersDowngradeAndException() {
        // Budget is $10.00. 
        // 1st massive call triggers WARNING first (Stage 1)
        assertThrows(BudgetWarningException.class,
                () -> governanceLayer.recordLlmCall(sessionId, 1000000, 1000000));

        // 2nd call triggers EXCEEDED (Stage 2)
        BudgetExceededException ex = assertThrows(BudgetExceededException.class,
                () -> governanceLayer.recordLlmCall(sessionId, 1, 1));

        assertTrue(ex.getMessage().contains("exceeded"));
        assertEquals(AutonomyLevel.READONLY, workspaceContext.getAutonomyLevel());
    }

    @Test
    void testSessionIsolation() {
        String sessionB = UUID.randomUUID().toString();
        WorkspaceScopeHolder.set(sessionB);
        try {
            // Stage 1: Warning
            try {
                governanceLayer.recordLlmCall(sessionB, 1000000, 1000000);
            } catch (BudgetWarningException e) {}
            
            // Stage 2: Exceeded
            try {
                governanceLayer.recordLlmCall(sessionB, 1, 1);
            } catch (BudgetExceededException e) {}
        } finally {
            WorkspaceScopeHolder.set(sessionId);
        }
        assertEquals(AutonomyLevel.FULL, workspaceContext.getAutonomyLevel());
    }
}
