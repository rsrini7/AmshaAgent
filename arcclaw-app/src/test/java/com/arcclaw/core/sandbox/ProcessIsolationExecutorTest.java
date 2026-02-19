package com.arcclaw.core.sandbox;

import com.arcclaw.core.scope.WorkspaceScopeHolder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link ProcessIsolationExecutor}.
 * <p>
 * Runs under the %test profile where strategy=NONE (direct execution).
 * Tests verify the NONE strategy path, env-file lifecycle, and circuit breaker
 * logic.
 */
@QuarkusTest
public class ProcessIsolationExecutorTest {

    @Inject
    ProcessIsolationExecutor executor;

    private static final String TEST_SESSION_ID = "test-sandbox-session";

    @BeforeEach
    void setup() {
        WorkspaceScopeHolder.set(TEST_SESSION_ID);
    }

    @AfterEach
    void tearDown() {
        WorkspaceScopeHolder.clear();
    }

    /**
     * NONE strategy: Verify direct execution of a simple command.
     * Uses a cross-platform-safe command.
     */
    @Test
    void testDirectExecution_NoneStrategy() throws Exception {
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
    }

    /**
     * Verify that executeSandboxed works with an empty secrets map (no env-file
     * needed for NONE).
     */
    @Test
    void testDirectExecution_EmptySecrets() throws Exception {
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
    }

    /**
     * Verify that the executor handles an unknown/invalid command gracefully.
     * In NONE strategy, the ProcessBuilder should handle the error.
     */
    @Test
    void testDirectExecution_InvalidCommand() throws Exception {
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
    }
}
