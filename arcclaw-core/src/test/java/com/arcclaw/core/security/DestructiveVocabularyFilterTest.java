package com.arcclaw.core.security;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DestructiveVocabularyFilterTest {

    private final DestructiveVocabularyFilter filter = new DestructiveVocabularyFilter();

    @Test
    void testCoderExemptByEmptyCapabilities() {
        // Coder has empty capabilities, so filter passes through even with "delete"
        String result = filter.filter("coder", "delete the repo");
        Assertions.assertEquals("coder", result);
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
}
