package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.util.Map;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class ToolRegistryDeadCatchTest {

    @Inject
    ToolRegistry registry;

    @Test
    void testInvokeToolWithUnknownRole() {
        // Call invokeTool with an unknown role string ("phantom_role").
        // Assert it returns a 403 failure and does not crash or silently swallow exceptions.
        WorkspaceContext.run("test-session", "user", "chat", AutonomyLevel.FULL, "phantom_role", () -> {
            ToolResponse response = registry.invokeTool(
                    ToolCapability.DELEGATE_TO_AGENT.toolName(), Map.of("agent", "coder", "goal", "list"));
            assertEquals(403, response.exitCode());
            assertTrue(
                    response.output().contains("Agent role 'phantom_role' is not permitted to use tool"),
                    "Actual output was: " + response.output());
        });
    }
}
