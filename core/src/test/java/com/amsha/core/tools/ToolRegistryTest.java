package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.inject.Inject;

import java.util.Collections;
import java.util.Map;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.RequiresSecret;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the Tool Registry - CDI discovery and governance
 * enforcement in core.
 */
@QuarkusTest
class ToolRegistryTest {

    private static final String TEST_SESSION = "test-toolregistry-session";

    @Inject
    ToolRegistry registry;

    @BeforeEach
    void activate() {
        // No-op. Scopes must be activated per-test using WorkspaceScopeHolder.run().
    }

    @AfterEach
    void deactivate() {
        // No-op.
    }

    /**
     * Verify that coordination tools are discovered via CDI Instance<AgentTool>.
     */
    @Test
    void testToolDiscovery() {
        var tools = registry.listTools();
        assertTrue(
                tools.contains(ToolCapability.DELEGATE_TO_AGENT.toolName()),
                "DelegateToAgentTool (garuda) should be discovered, found: " + tools);
    }

    /**
     * Verify that findTool returns the correct tool.
     */
    @Test
    void testFindToolByName() {
        var tool = registry.findTool(ToolCapability.DELEGATE_TO_AGENT.toolName());
        assertTrue(tool.isPresent(), "Should find 'garuda' tool");
        assertEquals(ToolCapability.DELEGATE_TO_AGENT.toolName(), tool.get().name());
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
     * Verify governance blocks unpermitted roles.
     */
    @Test
    void testGovernanceBlocksUnpermittedRole() {
        WorkspaceContext.run(TEST_SESSION, "unknown_role", "", AutonomyLevel.READONLY, "unknown_role", () -> {
            ToolResponse response = registry.invokeTool(
                    ToolCapability.DELEGATE_TO_AGENT.toolName(), Map.of("agent", "coder", "goal", "list"));

            assertEquals(403, response.exitCode(), "Should return 403 Forbidden");
        });
    }

    /**
     * Verify unknown tool invocation throws IllegalArgumentException.
     */
    @Test
    void testInvokeUnknownToolThrows() {
        WorkspaceContext.run(TEST_SESSION, "user", "chat", AutonomyLevel.FULL, "coordinator", () -> {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> registry.invokeTool("nonexistent-tool", Collections.emptyMap()));
        });
    }

    static class DummyTool implements AgentTool {
        @Override
        public String name() {
            return "dummy";
        }

        @Override
        public String description() {
            return "dummy tool";
        }

        @Override
        public ToolOperation requiredOperation() {
            return ToolOperation.EXECUTE_SHELL;
        }

        @Override
        public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
            return ToolResponse.success("ok");
        }

        @Override
        public JsonObjectSchema parametersSchema() {
            return JsonObjectSchema.builder().build();
        }

        @RequiresSecret(envVar = "DUMMY_KEY", vaultKey = "AMSHA_DUMMY_SECRET")
        public void sandboxCommand() {}
    }

    /**
     * Verify @RequiresSecret extraction.
     */
    @Test
    void testRequiresSecretExtraction() {
        var secrets = ToolRegistry.extractRequiredSecrets(DummyTool.class, "sandboxCommand");
        assertEquals(1, secrets.size(), "DummyTool should declare 1 secret");
        assertEquals(
                "AMSHA_DUMMY_SECRET",
                secrets.get("DUMMY_KEY"),
                "DUMMY_KEY env var should map to AMSHA_DUMMY_SECRET vault key");
    }

    @Test
    void secretsCachePrePopulatedAtStartup() {
        var garudaTool =
                registry.findTool(ToolCapability.DELEGATE_TO_AGENT.toolName()).get();
        var first = registry.extractRequiredSecrets(garudaTool);
        var second = registry.extractRequiredSecrets(garudaTool);
        assertTrue(first == second || first.equals(second), "Should match from cache");
    }
}
