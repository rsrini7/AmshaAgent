package com.amsha.app.tool;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.core.config.CoreSpiResolutionValidator;
import com.amsha.core.sandbox.SandboxExecutionPort;
import com.amsha.core.sandbox.SandboxFailureMessages;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.tools.AgentTool;
import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.tools.ToolResponse;
import com.amsha.core.tools.search.ToolSearchPort;
import com.amsha.mcp.tool.DynamicToolCapability;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.sandbox.ProcessIsolationExecutor;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tools.builtin.SandboxExecTool;
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
    ToolRegistry toolRegistry;

    @Inject
    SandboxExecTool sandboxExecTool;

    @Inject
    ToolSearchIndex toolSearchIndex;

    @Inject
    DynamicToolCapabilityRegistry dynamicToolRegistry;

    @Inject
    BeanManager beanManager;

    private List<AgentTool> discoverRawTools() {
        return beanManager.getBeans(Object.class).stream()
                .filter(bean -> bean != null && bean.getBeanClass() != null)
                .filter(bean -> (bean.getTypes() != null && bean.getTypes().contains(AgentTool.class))
                        || AgentTool.class.isAssignableFrom(bean.getBeanClass()))
                .sorted(Comparator.comparing(bean -> bean.getBeanClass().getName()))
                .map(this::getAgentToolReference)
                .toList();
    }

    private AgentTool getAgentToolReference(Bean<?> bean) {
        CreationalContext<?> context = beanManager.createCreationalContext(bean);
        return (AgentTool) beanManager.getReference(bean, AgentTool.class, context);
    }

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

    private static final List<String> EXPECTED_CREDENTIALLESS_ACTIVE_TOOLS = List.of(
            "agent_task",
            "archival_memory_insert",
            "archival_memory_search",
            "browser_navigate",
            "core_memory_append",
            "core_memory_read",
            "core_memory_replace",
            "garuda",
            "host_exec",
            "ingest_document",
            "manage_checklist",
            "manage_schedule",
            "recall_memory_search",
            "register_mcp_server",
            "sandbox_exec",
            "schedule_task",
            "self_evolve_reflect",
            "web_fetch");

    @Test
    void testRawCdiToolDiscoveryDiscoversAll21Tools() {
        Set<String> discoveredNames =
                discoverRawTools().stream().map(AgentTool::name).collect(Collectors.toSet());

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
    void testStartupDiscoveryEventBuildsSearchIndexAfterRegistrySnapshot() {
        int enabledStaticToolCount =
                (int) discoverRawTools().stream().filter(AgentTool::isEnabled).count();

        assertTrue(toolSearchIndex.isReady(), "Search index must be ready after registry startup event");
        assertEquals(
                enabledStaticToolCount,
                toolSearchIndex.size(),
                "Search index must contain the registry's startup tool snapshot");
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
    void testActiveManifestMatchesDiscoveredEnabledSet() {
        assertNotNull(toolRegistry);
        List<String> activeTools = toolRegistry.listTools();

        // Verify active list contains all unconditionally enabled tools
        for (String expectedTool : EXPECTED_CREDENTIALLESS_ACTIVE_TOOLS) {
            assertTrue(
                    activeTools.contains(expectedTool),
                    "Expected unconditional tool '" + expectedTool + "' missing from active manifest: " + activeTools);
        }

        List<String> expectedActiveTools = discoverRawTools().stream()
                .filter(AgentTool::isEnabled)
                .map(AgentTool::name)
                .sorted()
                .toList();
        assertEquals(expectedActiveTools, activeTools, "Active manifest must match the CDI enabled set exactly");

        // Verify every active tool is a valid member of the 21 raw CDI tools
        for (String activeTool : activeTools) {
            assertTrue(
                    EXPECTED_RAW_TOOL_NAMES.contains(activeTool),
                    "Active tool registry contains unexpected tool: " + activeTool);
        }
    }

    @Test
    void testDynamicMcpSeparationDoesNotInflateStaticCdiCount() {
        List<AgentTool> rawTools = discoverRawTools();
        long staticCount = rawTools.size();
        assertEquals(21, staticCount, "Static CDI tool count must remain exactly 21");

        // Verify dynamic invoker tools are isolated from static CDI bean set
        for (String staticName : EXPECTED_RAW_TOOL_NAMES) {
            assertTrue(
                    rawTools.stream().anyMatch(t -> t.name().equals(staticName)),
                    "Static CDI tool '" + staticName + "' must be present");
        }
    }

    @Test
    void testDynamicRegistryDoesNotMutateStaticManifest() throws Exception {
        List<String> staticManifestBefore =
                discoverRawTools().stream().map(AgentTool::name).sorted().toList();
        WorkspaceContext.run("dynamic-test", "dynamic-user", "dynamic-chat", AutonomyLevel.FULL, "coder", () -> {
            dynamicToolRegistry.register(new DynamicToolCapability(
                    "mcp_dynamic_test", "dynamic test tool", "test-server", McpTransportType.STDIO, false, "{}"));
            try {
                assertEquals(1, dynamicToolRegistry.listTools().size());
                assertEquals(
                        staticManifestBefore,
                        discoverRawTools().stream()
                                .map(AgentTool::name)
                                .sorted()
                                .toList());
            } finally {
                dynamicToolRegistry.deregister("mcp_dynamic_test");
            }
            return null;
        });
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
    void testProviderFailureSanitizationExcludesRawDiagnosticsFromBothPaths() throws Exception {
        // Path 1: ToolRegistry sandboxed invocation
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            ToolResponse response = toolRegistry.invokeTool(
                    "sandbox_exec",
                    Map.of("language", "python", "code", "raise RuntimeError('raw-provider-diagnostic')"));
            assertNotNull(response);
            assertFalse(response.success());

            // Assert raw internal diagnostics and sensitive infrastructure traces are absent
            String output = response.output();
            assertFalse(output.contains("NullPointerException"), "Output must not contain NullPointerException");
            assertFalse(output.contains("ToolExecutionException"), "Output must not contain ToolExecutionException");
            assertFalse(output.contains("Infrastructure failure"), "Output must not contain Infrastructure failure");
            assertFalse(output.contains("dial tcp"), "Output must not contain network dial errors");
            assertFalse(output.contains("Podman circuit"), "Output must not contain circuit breaker diagnostics");
            assertFalse(output.contains("raw-provider-diagnostic"), "Output must not contain provider diagnostics");
            return null;
        });

        // Path 2: Unknown / invalid language execution sanitized failure
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            ToolResponse response =
                    toolRegistry.invokeTool("sandbox_exec", Map.of("language", "invalid_lang_xyz", "code", "echo 1"));
            assertNotNull(response);
            assertFalse(response.success());

            String output = response.output();
            assertFalse(output.contains("NullPointerException"));
            assertFalse(output.contains("StackTrace"));
            return null;
        });
    }

    @Test
    void testDirectSandboxToolFailureSanitization() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            ToolExecutionContext context = new ToolExecutionContext(
                    "test-session",
                    "user1",
                    "chat1",
                    "coder",
                    AutonomyLevel.FULL,
                    "trace-1",
                    "test",
                    "platform",
                    "raw-provider-test",
                    null,
                    null,
                    null);
            ToolResponse response = sandboxExecTool.execute(
                    context, Map.of("language", "python", "code", "raise RuntimeError('raw-direct-diagnostic')"));
            assertFalse(response.success());
            assertFalse(response.output().contains("raw-direct-diagnostic"));
            return null;
        });
    }
}
