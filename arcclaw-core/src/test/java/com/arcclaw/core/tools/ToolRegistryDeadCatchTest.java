package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class ToolRegistryDeadCatchTest {

    @Inject
    ToolRegistry registry;

    @Test
    void testInvokeToolWithUnknownRole() {
        // Call invokeTool with an unknown role string ("phantom_role").
        // Assert it returns a 403 failure and does not crash or silently swallow exceptions.
        WorkspaceContext.run("test-session", "user", "chat", AutonomyLevel.FULL, "phantom_role", () -> {
            ToolResponse response = registry.invokeTool(ToolCapability.GITHUB.toolName(), Map.of("action", "repo", "command", "list"));
            assertEquals(403, response.exitCode());
            assertTrue(response.output().contains("Agent role 'phantom_role' is not permitted to use tool"), 
                "Actual output was: " + response.output());
        });
    }
}
