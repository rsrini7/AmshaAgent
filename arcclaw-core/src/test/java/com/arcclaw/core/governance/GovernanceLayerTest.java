package com.arcclaw.core.governance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class GovernanceLayerTest {

    @Inject
    GovernanceLayer governanceLayer;


    private String sessionId;

    @BeforeEach
    void setup() {
        sessionId = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() {
        governanceLayer.clearSession(sessionId);
    }

    @Test
    void testCostAccumulationUnderBudget() {
        WorkspaceContext.run(sessionId, "gov-user", "gov-chat", AutonomyLevel.FULL, "coordinator", () -> {
            // Price: In=$0.003/1k, Out=$0.015/1k
            // Let's record 1k input ($0.003) and 1k output ($0.015) -> Total: $0.018
            assertDoesNotThrow(() -> governanceLayer.recordLlmCall(sessionId, 1000, 1000));
            
            assertEquals(0.018, governanceLayer.getSessionCost(sessionId), 0.0001);
            assertEquals(1000, governanceLayer.getSessionInputTokens(sessionId));
            assertEquals(1000, governanceLayer.getSessionOutputTokens(sessionId));
        });
    }

    @Test
    void testBudgetWarningAt80Percent() {
        WorkspaceContext.run(sessionId, "gov-user", "gov-chat", AutonomyLevel.FULL, "coordinator", () -> {
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
        });
    }

    @Test
    void testBudgetExceededTriggersDowngradeAndException() {
        WorkspaceContext.run(sessionId, "gov-user", "gov-chat", AutonomyLevel.FULL, "coordinator", () -> {
            // Budget is $10.00. 
            // 1st call triggers WARNING (Stage 1) - Total: $8.10
            assertThrows(BudgetWarningException.class,
                    () -> governanceLayer.recordLlmCall(sessionId, 700000, 400000));

            // 2nd call triggers EXCEEDED (Stage 2) - Total: $10.20
            BudgetExceededException ex = assertThrows(BudgetExceededException.class,
                    () -> governanceLayer.recordLlmCall(sessionId, 200000, 100000));

            assertTrue(ex.getMessage().contains("exceeded"));
        });
    }

    @Test
    void testSessionIsolation() {
        String sessionB = UUID.randomUUID().toString();
        
        WorkspaceContext.run(sessionB, "gov-user", "gov-chat", AutonomyLevel.FULL, "coordinator", () -> {
            // Stage 1: Warning (Total $8.10)
            try {
                governanceLayer.recordLlmCall(sessionB, 700000, 400000);
            } catch (BudgetWarningException _) {}
            
            // Stage 2: Exceeded (Total $10.20)
            try {
                governanceLayer.recordLlmCall(sessionB, 200000, 100000);
            } catch (BudgetExceededException _) {}
        });

        WorkspaceContext.run(sessionId, "gov-user", "gov-chat", AutonomyLevel.FULL, "coordinator", () -> {
            assertEquals(0.0, governanceLayer.getSessionCost(sessionId), 0.0001);
        });
    }
    @Test
    void testZeroZeroTokenCountUsesFloor() {
        WorkspaceContext.run(sessionId, "gov-user", "gov-chat", AutonomyLevel.FULL, "coordinator", () -> {
            // (0,0) should use floor (10, 10)
            // Cost = (10/1000 * 0.003) + (10/1000 * 0.015) = 0.00003 + 0.00015 = 0.00018
            assertDoesNotThrow(() -> governanceLayer.recordLlmCall(sessionId, 0, 0));
            
            assertEquals(0.00018, governanceLayer.getSessionCost(sessionId), 0.000001);
            assertTrue(governanceLayer.getSessionCost(sessionId) > 0.0);
            assertEquals(10, governanceLayer.getSessionInputTokens(sessionId));
            assertEquals(10, governanceLayer.getSessionOutputTokens(sessionId));
        });
    }

    @Test
    void testPartialZeroTokenCountIsNotInflated() {
        WorkspaceContext.run(sessionId, "gov-user", "gov-chat", AutonomyLevel.FULL, "coordinator", () -> {
            // (3,0) is valid, should NOT use floor.
            // Cost = (3/1000 * 0.003) + (0/1000 * 0.015) = 0.000009
            assertDoesNotThrow(() -> governanceLayer.recordLlmCall(sessionId, 3, 0));
            
            assertEquals(0.000009, governanceLayer.getSessionCost(sessionId), 0.0000001);
            assertEquals(3, governanceLayer.getSessionInputTokens(sessionId));
            assertEquals(0, governanceLayer.getSessionOutputTokens(sessionId));
        });
    }

    @Test
    void testNormalTokenCountNotOverridden() {
        WorkspaceContext.run(sessionId, "gov-user", "gov-chat", AutonomyLevel.FULL, "coordinator", () -> {
            // (500, 200) -> Cost = (500/1000 * 0.003) + (200/1000 * 0.015) = 0.0015 + 0.003 = 0.0045
            assertDoesNotThrow(() -> governanceLayer.recordLlmCall(sessionId, 500, 200));
            
            assertEquals(0.0045, governanceLayer.getSessionCost(sessionId), 0.0001);
            assertEquals(500, governanceLayer.getSessionInputTokens(sessionId));
            assertEquals(200, governanceLayer.getSessionOutputTokens(sessionId));
        });
    }

    @Test
    void testHardLimitEnforcedOnSingleMassiveCall() {
        WorkspaceContext.run(sessionId, "gov-user", "gov-chat", AutonomyLevel.FULL, "coordinator", () -> {
            // Budget is $10.00. 10 million input tokens = $30.00. 
            // This exceeds both warning ($8.00) and hard limit ($10.00).
            // It MUST throw BudgetExceededException, not BudgetWarningException.
            assertThrows(BudgetExceededException.class,
                    () -> governanceLayer.recordLlmCall(sessionId, 10_000_000, 0));
        });
    }

    @Inject
    org.eclipse.microprofile.context.ManagedExecutor managedExecutor;

    @Test
    void testConcurrentCostAccumulationIsAtomic() throws Exception {
        WorkspaceContext.run(sessionId, "gov-user", "gov-chat", AutonomyLevel.FULL, "coordinator", () -> {
            // Each call adds $0.018 (1k in, 1k out). 
            // 10 concurrent calls should result in exactly $0.18.
            int concurrency = 10;
            java.util.concurrent.CompletableFuture<?>[] futures = new java.util.concurrent.CompletableFuture[concurrency];
            
            for (int i = 0; i < concurrency; i++) {
                futures[i] = managedExecutor.supplyAsync(() -> {
                    governanceLayer.recordLlmCall(sessionId, 1000, 1000);
                    return null;
                });
            }
            
            java.util.concurrent.CompletableFuture.allOf(futures).join();
            
            assertEquals(0.18, governanceLayer.getSessionCost(sessionId), 0.0001);
            assertEquals(1000 * concurrency, governanceLayer.getSessionInputTokens(sessionId));
            assertEquals(1000 * concurrency, governanceLayer.getSessionOutputTokens(sessionId));
        });
    }
}
