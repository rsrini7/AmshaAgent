package com.amsha.core.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class GovernanceBoundingTest {

    @Inject
    GovernanceLayer governanceLayer;

    @Test
    void testExplicitClearSessionWorks() {
        String sessionId = UUID.randomUUID().toString();
        governanceLayer.recordLlmCall(sessionId, 100, 100);
        assertTrue(governanceLayer.getSessionCost(sessionId) > 0);

        governanceLayer.clearSession(sessionId);
        assertEquals(0.0, governanceLayer.getSessionCost(sessionId));
    }

    @Test
    void testScheduledCleanupEvictsStaleEntries() throws Exception {
        String staleSession = "stale-" + UUID.randomUUID();
        String freshSession = "fresh-" + UUID.randomUUID();

        // 1. Create sessions
        governanceLayer.recordLlmCall(staleSession, 100, 100);
        governanceLayer.recordLlmCall(freshSession, 100, 100);

        // 2. Manipulate lastAccessedAt for the stale one.
        var budgets = governanceLayer.getBudgetsInternal();
        GovernanceLayer.SessionBudget budgetObj = budgets.get(staleSession);
        assertNotNull(budgetObj, "Stale budget should exist in map");

        // Set to 25 hours ago
        budgetObj.setLastAccessedAt(Instant.now().minus(25, ChronoUnit.HOURS));

        // 3. Trigger cleanup
        governanceLayer.cleanupStaleBudgets();

        // 4. Verify
        assertEquals(0.0, governanceLayer.getSessionCost(staleSession), "Stale session should be evicted");
        assertTrue(governanceLayer.getSessionCost(freshSession) > 0, "Fresh session should remain");
    }
}
