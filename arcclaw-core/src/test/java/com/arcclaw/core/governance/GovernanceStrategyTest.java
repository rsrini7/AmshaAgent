package com.arcclaw.core.governance;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class GovernanceStrategyTest {

    @Inject GovernanceStrategy governanceLayer; // Must inject via interface

    @Test
    void interfaceIsInjectableInsteadOfConcreteClass() {
        assertNotNull(governanceLayer);
        assertTrue(governanceLayer instanceof GovernanceLayer);
    }

    @Test
    void recordLlmCallThrowsBudgetExceededWhenLimitHit() {
        // Force exceed by recording massive token counts
        assertThrows(BudgetExceededException.class, () ->
            governanceLayer.recordLlmCall("test-session-999", 10_000_000, 10_000_000)
        );
        governanceLayer.clearSession("test-session-999");
    }

    @Test
    void clearSessionResetsState() {
        governanceLayer.recordLlmCall("session-clear-test", 10, 10);
        governanceLayer.clearSession("session-clear-test");
        assertEquals(0.0, governanceLayer.getSessionCost("session-clear-test"));
    }
}
