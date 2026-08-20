package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DirectExecutorTest {

    @Test
    void testDirectExecutionEcho() throws Exception {
        DirectExecutor executor = new DirectExecutor();
        executor.timeoutSeconds = 5;
        Path tempDir = Files.createTempDirectory("direct-exec-test");

        String output = executor.executeDirectly(
                tempDir, List.of("echo", "direct_ok"), Map.of("KEY", "VAL".getBytes(StandardCharsets.UTF_8)));
        assertNotNull(output);
        assertTrue(output.contains("direct_ok"));
    }

    @Test
    void testDirectExecutionInvalidCommand() throws Exception {
        DirectExecutor executor = new DirectExecutor();
        executor.timeoutSeconds = 5;
        Path tempDir = Files.createTempDirectory("direct-exec-err");

        assertThrows(Exception.class, () -> {
            executor.executeDirectly(tempDir, List.of("non_existent_binary_xyz_123"), Map.of());
        });
    }
}
