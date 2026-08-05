package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.sandbox.ProcessIsolationExecutor;
import com.amsha.core.security.ToolOperation;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.core.tool.DynamicToolDescriptor;
import com.amsha.core.tool.DynamicToolInvoker;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolRegistryUnitTest {

    @Mock
    ToolDiscoveryService discoveryService;

    @Mock
    ToolGovernanceGuard governanceGuard;

    @Mock
    ToolSecretExtractor secretExtractor;

    @Mock
    ProcessIsolationExecutor isolationExecutor;

    @Mock
    ObjectMapper objectMapper;

    @Mock
    AuditService auditService;

    @Mock
    SkillRegistry skillRegistry;

    @Mock
    Instance<DynamicToolInvoker> dynamicToolInvokers;

    @Mock
    DynamicToolInvoker dynamicToolInvoker;

    @InjectMocks
    ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry.workspaceRootDir = "./target/test-workspaces";
    }

    @Test
    void testInitialize() {
        registry.initialize();
        verify(discoveryService).discover();
    }

    @Test
    void testFindToolUnknown() {
        when(discoveryService.getToolIndex()).thenReturn(Map.of());
        assertTrue(registry.findTool("unknown").isEmpty());
    }

    @Test
    void testFindToolFound() {
        AgentTool tool = mock(AgentTool.class);
        when(discoveryService.getToolIndex()).thenReturn(Map.of("sandbox_exec", tool));
        assertEquals(tool, registry.findTool("sandbox_exec").orElse(null));
    }

    @Test
    void testListTools() {
        AgentTool t1 = mock(AgentTool.class);
        when(discoveryService.getToolIndex()).thenReturn(Map.of("t1", t1));

        List<String> tools = registry.listTools();
        assertEquals(1, tools.size());
        assertEquals("t1", tools.get(0));
        assertEquals("t1", registry.registeredToolNames());
    }

    @Test
    void invokesDynamicToolThroughTheRegistry() {
        ToolResponse expected = ToolResponse.success("mcp-result");
        DynamicToolDescriptor descriptor = new DynamicToolDescriptor(
                "mcp_tool", "MCP tool", "{}", ToolCategory.NETWORK, false, ToolOperation.NETWORK_OUTBOUND);
        when(discoveryService.getToolIndex()).thenReturn(Map.of());
        when(dynamicToolInvokers.isUnsatisfied()).thenReturn(false);
        when(dynamicToolInvokers.iterator())
                .thenReturn(List.of(dynamicToolInvoker).iterator());
        when(dynamicToolInvoker.findTool("mcp_tool")).thenReturn(Optional.of(descriptor));
        when(dynamicToolInvoker.invoke(eq("mcp_tool"), eq(Map.of("value", "x")), any()))
                .thenReturn(expected);

        ToolResponse actual = registry.invokeTool("mcp_tool", Map.of("value", "x"));

        assertSame(expected, actual);
        verify(dynamicToolInvoker).invoke(eq("mcp_tool"), eq(Map.of("value", "x")), any());
    }

    @Test
    void testToSafeDirName() {
        assertEquals("session_1", ToolRegistry.toSafeDirName("session:1"));
    }
}
