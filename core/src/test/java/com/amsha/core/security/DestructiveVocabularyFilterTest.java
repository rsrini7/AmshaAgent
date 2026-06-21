package com.amsha.core.security;

import com.amsha.core.agent.role.AgentRoleEnum;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DestructiveVocabularyFilterTest {

    private final DestructiveVocabularyFilter filter = createFilter();

    private static DestructiveVocabularyFilter createFilter() {
        DestructiveVocabularyFilter f = new DestructiveVocabularyFilter();
        f.policyConfig = PolicyConfigTestHelper.defaultConfig();
        f.init();
        return f;
    }

    @Test
    void testCoderExemptBySandboxOnlyCapabilities() {
        // Coder is exempt (sandbox-only role via config)
        String result = filter.filter(AgentRoleEnum.CODER.id(), "delete the broken class");
        Assertions.assertEquals(
                AgentRoleEnum.CODER.id(),
                result,
                "Coder must be exempt from destructive vocabulary filter - sandbox-only role");
    }

    @Test
    void testDeleteDetected_researcher() {
        // Researcher has capabilities, "delete" is destructive
        String result = filter.filter(AgentRoleEnum.RESEARCHER.id(), "delete the repo");
        Assertions.assertEquals(AgentRoleEnum.COORDINATOR.id(), result);
    }

    @Test
    void testRmRfWithSpaceDetected() {
        String result = filter.filter(AgentRoleEnum.RESEARCHER.id(), "rm -rf /data");
        Assertions.assertEquals(AgentRoleEnum.COORDINATOR.id(), result);
    }

    @Test
    void testRmRfNoSpaceDetected() {
        String result = filter.filter(AgentRoleEnum.RESEARCHER.id(), "rm-rf everything");
        Assertions.assertEquals(AgentRoleEnum.COORDINATOR.id(), result);
    }

    @Test
    void testRmRfCamelCaseDetected() {
        String result = filter.filter(AgentRoleEnum.RESEARCHER.id(), "Run RM -RF on it");
        Assertions.assertEquals(AgentRoleEnum.COORDINATOR.id(), result);
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
        String result = filter.filter(AgentRoleEnum.RESEARCHER.id(), "write a function to list files");
        Assertions.assertEquals(AgentRoleEnum.RESEARCHER.id(), result);
    }

    @Test
    void testDestructiveFilterFallbackToCoordinator() {
        String result = filter.filter(AgentRoleEnum.RESEARCHER.id(), "delete the repo");
        Assertions.assertEquals(AgentRoleEnum.COORDINATOR.id(), result);
    }
}
