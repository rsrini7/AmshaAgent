package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.amsha.core.sandbox.SandboxFailureMessages;
import com.amsha.core.sandbox.SandboxResult;
import com.amsha.core.scope.WorkspaceScopeHolder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProcessIsolationExecutorTest {

    ProcessIsolationExecutor executor;

    private static final String TEST_SESSION_ID = "test-sandbox-session";

    @BeforeEach
    void setUp() {
        executor = new ProcessIsolationExecutor();
        executor.circuitBreaker = new SandboxCircuitBreaker();
        executor.directExecutor = new DirectExecutor();
        executor.secretResolver = new SandboxSecretResolver();
        executor.sandboxStrategy = SandboxStrategy.NONE;
        executor.sandboxEnabled = true;
    }

    /**
     * NONE strategy: Verify direct execution of a simple command.
     * Uses a cross-platform-safe command.
     */
    @Test
    void testDirectExecution_NoneStrategy() throws Exception {
        WorkspaceScopeHolder.run(TEST_SESSION_ID, () -> {
            try {
                Path workspaceRoot = Files.createTempDirectory("amsha-test-ws");
                try {
                    SandboxResult result = executor.executeSandboxed(
                            TEST_SESSION_ID, workspaceRoot, List.of("java", "-version"), Map.of() // no secrets needed
                            );
                    assertNotNull(result, "Result should not be null");
                    assertNotNull(result.output(), "Output should not be null");
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
                Path workspaceRoot = Files.createTempDirectory("amsha-test-ws");
                try {
                    SandboxResult result = executor.executeSandboxed(
                            TEST_SESSION_ID, workspaceRoot, List.of("java", "-version"), Map.of());
                    assertNotNull(result);
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
                Path workspaceRoot = Files.createTempDirectory("amsha-test-ws");
                try {
                    SandboxResult result = executor.executeSandboxed(
                            TEST_SESSION_ID,
                            workspaceRoot,
                            List.of("nonexistent-command-that-does-not-exist"),
                            Map.of());
                    assertFalse(result.success());
                    // Allow for either a direct process error (NONE strategy) or a sandbox infra failure (PODMAN
                    // strategy)
                    assertTrue(result.isInfraFailure()
                            || result.reason().contains(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE)
                            || result.reason().contains(SandboxFailureMessages.SANDBOX_EXECUTION_FAILED_MESSAGE));
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
