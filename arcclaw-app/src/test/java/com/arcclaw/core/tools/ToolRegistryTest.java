package com.arcclaw.core.tools;

import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.sandbox.ProcessIsolationExecutor;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Tool Registry — CDI discovery and governance
 * enforcement.
 * <p>
 * Uses the {@code test} profile (NONE sandbox strategy) so no Podman is
 * required.
 */
@QuarkusTest
class ToolRegistryTest {

    private static final String TEST_SESSION = "test-toolregistry-session";

    @Inject
    ToolRegistry registry;

    @InjectMock
    ProcessIsolationExecutor mockIsolationExecutor;

    @Inject
    WorkspaceContext workspaceContext;

    @BeforeEach
    void activate() {
        WorkspaceScopeHolder.set(TEST_SESSION);
    }

    @AfterEach
    void deactivate() {
        WorkspaceScopeHolder.clear();
    }

    /**
     * Verify that GithubTool is discovered via CDI Instance<AgentTool>.
     */
    @Test
    void testToolDiscovery() {
        var tools = registry.listTools();
        assertTrue(tools.contains("github"),
                "GithubTool should be discovered, found: " + tools);
    }

    /**
     * Verify that findTool returns the correct tool.
     */
    @Test
    void testFindToolByName() {
        var tool = registry.findTool("github");
        assertTrue(tool.isPresent(), "Should find 'github' tool");
        assertEquals("github", tool.get().name());
        assertNotNull(tool.get().description());
    }

    /**
     * Verify that findTool returns empty for unknown tools.
     */
    @Test
    void testFindUnknownToolReturnsEmpty() {
        var tool = registry.findTool("nonexistent");
        assertTrue(tool.isEmpty(), "Should not find unknown tool");
    }

    /**
     * Verify governance: READ_ONLY blocks NETWORK_OUTBOUND tools.
     */
    @Test
    void testGovernanceBlocksNetworkOutbound() {
        workspaceContext.setAutonomyLevel(AutonomyLevel.READONLY);

        var ex = assertThrows(SecurityException.class,
                () -> registry.invokeTool("github", Map.<String, Object>of("repo", "test/repo")));

        assertTrue(ex.getMessage().contains("NETWORK_OUTBOUND"),
                "SecurityException should mention the blocked operation");
        assertTrue(ex.getMessage().contains("READONLY"),
                "SecurityException should mention the autonomy level");
    }

    /**
     * Verify governance: FULL autonomy allows tool invocation.
     * The tool will fail because 'gh' is not installed, but the governance
     * check should pass — we verify by checking that we did NOT get a
     * SecurityException.
     */
    @Test
    void testGovernanceAllowsFullAutonomy() {
        workspaceContext.setAutonomyLevel(AutonomyLevel.FULL);

        // This will *not* throw SecurityException (governance passes).
        // The tool itself may fail (no 'gh' binary) — that's fine; we're
        // testing governance, not the gh CLI.
        ToolResponse response = registry.invokeTool("github", Map.<String, Object>of("repo", "test/repo"));
        assertNotNull(response, "Should get a response (even if tool execution failed)");
    }

    /**
     * Verify @RequiresSecret extraction from GithubTool.
     */
    @Test
    void testRequiresSecretExtraction() {
        var secrets = ToolRegistry.extractRequiredSecrets(GithubTool.class, "sandboxCommand");
        assertEquals(1, secrets.size(), "GithubTool should declare 1 secret");
        assertEquals("GITHUB_TOKEN", secrets.get("GH_TOKEN"),
                "GH_TOKEN env var should map to GITHUB_TOKEN vault key");
    }

    /**
     * Verify unknown tool invocation throws IllegalArgumentException.
     */
    @Test
    void testInvokeUnknownToolThrows() {
        workspaceContext.setAutonomyLevel(AutonomyLevel.FULL);
        assertThrows(IllegalArgumentException.class,
                () -> registry.invokeTool("nonexistent-tool", java.util.Collections.emptyMap()));
    }
}
