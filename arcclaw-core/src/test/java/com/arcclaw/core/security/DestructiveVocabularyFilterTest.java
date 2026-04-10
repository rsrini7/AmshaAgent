package com.arcclaw.core.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DestructiveVocabularyFilterTest {

    private final DestructiveVocabularyFilter filter = new DestructiveVocabularyFilter();

    @Test
    void testCoderExemptBySandboxOnlyCapabilities() {
        // Coder has exactly SANDBOX_EXEC — still exempt (sandbox is controlled execution)
        assertTrue(filter.isSandboxOnlyRole(AgentRoleEnum.CODER),
            "Coder must be exempt from destructive vocabulary filter — sandbox-only role");
        // Coordinator has broad tools — must NOT be exempt
        assertFalse(filter.isSandboxOnlyRole(AgentRoleEnum.COORDINATOR),
            "Coordinator must not be exempt — has network/schedule capabilities");
    }

    @Test
    void testDeleteDetected_researcher() {
        // Researcher has capabilities, "delete" is destructive
        String result = filter.filter("researcher", "delete the repo");
        Assertions.assertEquals("coordinator", result);
    }

    @Test
    void testRmRfWithSpaceDetected() {
        String result = filter.filter("researcher", "rm -rf /data");
        Assertions.assertEquals("coordinator", result);
    }

    @Test
    void testRmRfNoSpaceDetected() {
        String result = filter.filter("researcher", "rm-rf everything");
        Assertions.assertEquals("coordinator", result);
    }

    @Test
    void testRmRfCamelCaseDetected() {
        String result = filter.filter("researcher", "Run RM -RF on it");
        Assertions.assertEquals("coordinator", result);
    }

    @Test
    void testCoordinatorRolePassesThrough() {
        // Coordinator is already the target, should pass through
        String result = filter.filter("coordinator", "delete the repo");
        Assertions.assertEquals("coordinator", result);
    }

    @Test
    void testDebuggerExemptFromFilter() {
        // Debugger is explicitly exempt
        String result = filter.filter("debugger", "delete the broken class");
        Assertions.assertEquals("debugger", result);
    }

    @Test
    void testNonDestructiveMessagePassesThrough() {
        String result = filter.filter("researcher", "write a function to list files");
        Assertions.assertEquals("researcher", result);
    }

    @Test
    void testDestructiveFilterFallbackToCoordinator() {
        String result = filter.filter("researcher", "delete the repo");
        Assertions.assertEquals(AgentRoleEnum.COORDINATOR.id(), result);
    }
}
