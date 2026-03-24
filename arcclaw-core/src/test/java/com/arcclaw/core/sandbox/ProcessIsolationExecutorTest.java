package com.arcclaw.core.sandbox;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import com.arcclaw.core.scope.WorkspaceScopeHolder;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class ProcessIsolationExecutorTest {

    @Inject
    ProcessIsolationExecutor executor;

    private static final String TEST_SESSION_ID = "test-sandbox-session";

    /**
     * NONE strategy: Verify direct execution of a simple command.
     * Uses a cross-platform-safe command.
     */
    @Test
    void testDirectExecution_NoneStrategy() throws Exception {
        WorkspaceScopeHolder.run(TEST_SESSION_ID, () -> {
            try {
                Path workspaceRoot = Files.createTempDirectory("arcclaw-test-ws");
                try {
                    // 'java -version' works reliably on all platforms since JDK is required
                    String output = executor.executeSandboxed(
                            TEST_SESSION_ID,
                            workspaceRoot,
                            List.of("java", "-version"),
                            Map.of() // no secrets needed
                    );
                    assertNotNull(output, "Output should not be null");
                    // java -version writes to stderr which is redirected — output may contain
                    // version info
                } finally {
                    Files.deleteIfExists(workspaceRoot);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Verify that executeSandboxed works with an empty secrets map (no env-file
     * needed for NONE).
     */
    @Test
    void testDirectExecution_EmptySecrets() throws Exception {
        WorkspaceScopeHolder.run(TEST_SESSION_ID, () -> {
            try {
                Path workspaceRoot = Files.createTempDirectory("arcclaw-test-ws");
                try {
                    String output = executor.executeSandboxed(
                            TEST_SESSION_ID,
                            workspaceRoot,
                            List.of("java", "-version"),
                            Map.of());
                    assertNotNull(output);
                } finally {
                    Files.deleteIfExists(workspaceRoot);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Verify that the executor handles an unknown/invalid command gracefully.
     * In NONE strategy, the ProcessBuilder should handle the error.
     */
    @Test
    void testDirectExecution_InvalidCommand() throws Exception {
        WorkspaceScopeHolder.run(TEST_SESSION_ID, () -> {
            try {
                Path workspaceRoot = Files.createTempDirectory("arcclaw-test-ws");
                try {
                    assertThrows(IOException.class, () -> executor.executeSandboxed(
                            TEST_SESSION_ID,
                            workspaceRoot,
                            List.of("nonexistent-command-that-does-not-exist"),
                            Map.of()));
                } finally {
                    Files.deleteIfExists(workspaceRoot);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void testCircuitBreakerLifecycle() {
        // Since we can't easily mock the internal podman executions without a lot of ceremony,
        // we'll at least verify the bean is injectable and the config properties are active.
        Assertions.assertNotNull(executor);
        Assertions.assertTrue(executor.getResetTimeoutSeconds() >= 0);
        Assertions.assertTrue(executor.getPodmanFailureThreshold() > 0);
    }
}
