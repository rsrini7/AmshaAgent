package com.amsha.security;

import static org.junit.jupiter.api.Assertions.*;

import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;

import org.junit.jupiter.api.Test;

/**
 * Pins the standard autonomy mapping (moved from core in S7). Core's ToolRegistry and
 * ToolGovernanceGuard consume the {@code AutonomyPolicy} interface; this impl is the
 * default provider.
 */
class StandardAutonomyPolicyTest {

    private final StandardAutonomyPolicy policy = new StandardAutonomyPolicy();

    @Test
    void fullAutonomyAllowsEveryOperation() {
        for (ToolOperation op : ToolOperation.values()) {
            assertTrue(policy.isAllowed(AutonomyLevel.FULL, op), op.name());
        }
    }

    @Test
    void supervisedAllowsOnlyReadAndScheduling() {
        assertTrue(policy.isAllowed(AutonomyLevel.SUPERVISED, ToolOperation.READ_FS));
        assertTrue(policy.isAllowed(AutonomyLevel.SUPERVISED, ToolOperation.SCHEDULE_AUTONOMOUS));
        assertFalse(policy.isAllowed(AutonomyLevel.SUPERVISED, ToolOperation.NETWORK_OUTBOUND));
    }

    @Test
    void backgroundNeverAllowsShellExecution() {
        assertFalse(policy.isAllowed(AutonomyLevel.BACKGROUND, ToolOperation.EXECUTE_SHELL));
        assertTrue(policy.isAllowed(AutonomyLevel.BACKGROUND, ToolOperation.READ_FS));
        assertTrue(policy.isAllowed(AutonomyLevel.BACKGROUND, ToolOperation.NETWORK_OUTBOUND));
        assertTrue(policy.isAllowed(AutonomyLevel.BACKGROUND, ToolOperation.DELEGATE_AUTONOMOUS));
    }

    @Test
    void readOnlyAllowsOnlyFilesystemReads() {
        assertTrue(policy.isAllowed(AutonomyLevel.READONLY, ToolOperation.READ_FS));
        assertFalse(policy.isAllowed(AutonomyLevel.READONLY, ToolOperation.NETWORK_OUTBOUND));
        assertFalse(policy.isAllowed(AutonomyLevel.READONLY, ToolOperation.EXECUTE_SHELL));
    }
}
