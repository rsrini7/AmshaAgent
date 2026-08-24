package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class NamespaceExecutorTest {

    @Test
    void testNamespaceExecutorEcho() throws Exception {
        NamespaceExecutor executor = new NamespaceExecutor();
        executor.timeoutSeconds = 5;
        Path tempDir = Files.createTempDirectory("ns-exec-test");

        String output = executor.executeInNamespace(tempDir, List.of("echo", "ns_ok"));
        assertNotNull(output);
        assertTrue(output.contains("ns_ok"));
    }

    @Test
    void testNamespaceExecutorInvalidCommand() throws Exception {
        NamespaceExecutor executor = new NamespaceExecutor();
        executor.timeoutSeconds = 5;
        Path tempDir = Files.createTempDirectory("ns-exec-err");

        assertThrows(Exception.class, () -> {
            executor.executeInNamespace(tempDir, List.of("non_existent_binary_xyz_123"));
        });
    }
}
