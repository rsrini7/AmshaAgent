package com.amsha.app.tool;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.core.config.CoreSpiResolutionValidator;
import com.amsha.core.sandbox.SandboxExecutionPort;
import com.amsha.core.sandbox.SandboxFailureMessages;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.tools.AgentTool;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.tools.ToolResponse;
import com.amsha.core.tools.search.ToolSearchPort;
import com.amsha.sandbox.ProcessIsolationExecutor;
import com.amsha.tools.builtin.ToolSearchIndex;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration test verifying that all 21 concrete AgentTool implementations across all modules,
 * the SandboxExecutionPort provider, and the ToolSearchPort provider are discovered
 * by Quarkus Arc CDI at application runtime, maintaining deterministic ordering,
 * dynamic MCP separation, and compliant SPI resolution.
 */
@QuarkusTest
class ToolDiscoveryIntegrationTest {

    @Inject
    @Any
    Instance<AgentTool> tools;

    @Inject
    ToolRegistry toolRegistry;

    @Inject
    BeanManager beanManager;

    private static final Set<String> EXPECTED_RAW_TOOL_NAMES = Set.of(
            "brave_web_search",
            "serper_web_search",
            "web_fetch",
            "host_exec",
            "github",
            "schedule_task",
            "manage_schedule",
            "manage_checklist",
            "sandbox_exec",
            "archival_memory_insert",
            "archival_memory_search",
            "core_memory_append",
            "core_memory_read",
            "core_memory_replace",
            "recall_memory_search",
            "garuda",
            "agent_task",
            "ingest_document",
            "register_mcp_server",
            "self_evolve_reflect",
            "browser_navigate");

    @Test
    void testRawCdiToolDiscoveryDiscoversAll21Tools() {
        Set<String> discoveredNames = tools.stream().map(AgentTool::name).collect(Collectors.toSet());

        assertEquals(21, discoveredNames.size(), "Expected exactly 21 raw CDI AgentTool beans");
        assertEquals(EXPECTED_RAW_TOOL_NAMES, discoveredNames, "Discovered tool names must match raw CDI manifest");
    }

    @Test
    void testSandboxExecutionPortResolvesViaCompliantAlgorithm() {
        Optional<SandboxExecutionPort> portOpt =
                CoreSpiResolutionValidator.resolveOptional(beanManager, SandboxExecutionPort.class);
        assertTrue(portOpt.isPresent(), "SandboxExecutionPort must resolve via CoreSpiResolutionValidator");
        assertInstanceOf(ProcessIsolationExecutor.class, portOpt.get());
    }

    @Test
    void testToolSearchPortResolvesViaCompliantAlgorithm() {
        Optional<ToolSearchPort> portOpt =
                CoreSpiResolutionValidator.resolveOptional(beanManager, ToolSearchPort.class);
        assertTrue(portOpt.isPresent(), "ToolSearchPort must resolve via CoreSpiResolutionValidator");
        assertInstanceOf(ToolSearchIndex.class, portOpt.get());
        ToolSearchPort port = portOpt.get();
        assertTrue(port.isReady(), "ToolSearchIndex must be ready after tool discovery completes");
        List<String> results = port.search("web search", 5);
        assertNotNull(results, "Search results should not be null");
    }

    @Test
    void testToolRegistryMaintainsDeterministicSortedOrder() {
        assertNotNull(toolRegistry);
        List<String> registeredTools = toolRegistry.listTools();
        assertNotNull(registeredTools);
        assertFalse(registeredTools.isEmpty(), "Active tool registry should contain registered tools");

        List<String> sortedNames = new ArrayList<>(registeredTools);
        Collections.sort(sortedNames);
        assertEquals(
                sortedNames, registeredTools, "Tool registry must return names in deterministic alphabetical order");
    }

    @Test
    void testDynamicMcpSeparationDoesNotInflateStaticCdiCount() {
        long staticCount = tools.stream().count();
        assertEquals(21, staticCount, "Static CDI tool count must remain exactly 21 regardless of dynamic MCP tools");
    }

    @Test
    void testActiveToolRegistryManifestMatchesDiscoveredEnabledSet() {
        assertNotNull(toolRegistry);
        List<String> registeredTools = toolRegistry.listTools();
        assertNotNull(registeredTools);
        // All active registered tools must be members of the raw manifest
        for (String toolName : registeredTools) {
            assertTrue(
                    EXPECTED_RAW_TOOL_NAMES.contains(toolName),
                    "Active tool registry contains unexpected tool: " + toolName);
        }
    }

    @Test
    void testDisabledOrShadowSpiHandling() {
        interface NonExistentSpi {}
        Optional<NonExistentSpi> nonExistent =
                CoreSpiResolutionValidator.resolveOptional(beanManager, NonExistentSpi.class);
        assertTrue(nonExistent.isEmpty(), "Unregistered or shadow SPI should resolve to empty");
    }

    @Test
    void testSanitizedSandboxFailureMessageConstant() {
        assertEquals(
                "Sandbox execution environment is currently unavailable.",
                SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE);
        assertEquals(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE, SandboxExecutionPort.SANDBOX_OFFLINE_MESSAGE);
    }

    @Test
    void testProviderFailureSanitizationExcludesRawDiagnostics() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            ToolResponse response =
                    toolRegistry.invokeTool("sandbox_exec", Map.of("language", "python", "code", "exit(1)"));
            assertNotNull(response);
            assertFalse(response.success());
            assertFalse(response.output().contains("NullPointerException"));
            return null;
        });
    }
}
