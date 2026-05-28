package com.arcclaw.mcp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.tools.ToolSchemaProvider;
import com.arcclaw.mcp.config.McpToolAllowlist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;

class McpToolBridgeTest {

    private McpToolBridge bridge;
    private ToolRegistry mockRegistry;
    private McpToolAllowlist mockAllowlist;
    private ToolSchemaProvider mockSchemaProvider;

    @BeforeEach
    void setUp() {
        bridge = new McpToolBridge();
        mockRegistry = mock(ToolRegistry.class);
        mockAllowlist = mock(McpToolAllowlist.class);
        mockSchemaProvider = mock(ToolSchemaProvider.class);
        bridge.toolRegistry = mockRegistry;
        bridge.allowlist = mockAllowlist;
        bridge.schemaProvider = mockSchemaProvider;
        bridge.auditService = mock(AuditService.class);
        bridge.clearCache();
    }

    @Test
    void testGetExposedTools() throws Exception {
        when(mockAllowlist.exposedTools())
                .thenReturn(List.of(ToolCapability.GITHUB.toolName(), ToolCapability.RECALL_MEMORY_SEARCH.toolName()));
        AgentTool mockTool = mock(AgentTool.class);
        when(mockRegistry.findTool(anyString())).thenReturn(Optional.of(mockTool));
        when(mockSchemaProvider.getJsonSchema(ArgumentMatchers.any())).thenReturn("{}");

        List<McpToolDescriptor> tools = bridge.getExposedTools();
        assertEquals(2, tools.size());
        assertEquals(ToolCapability.GITHUB.toolName(), tools.get(0).name());
        assertEquals(
                ToolCapability.RECALL_MEMORY_SEARCH.toolName(), tools.get(1).name());
    }

    @Test
    void testGetExposedToolsSchemaDerivation() throws Exception {
        when(mockAllowlist.exposedTools()).thenReturn(List.of(ToolCapability.GITHUB.toolName()));
        AgentTool mockTool = mock(AgentTool.class);
        when(mockRegistry.findTool(ToolCapability.GITHUB.toolName())).thenReturn(Optional.of(mockTool));
        when(mockSchemaProvider.getJsonSchema(mockTool)).thenReturn("{\"schema\":\"v1\"}");

        List<McpToolDescriptor> tools = bridge.getExposedTools();
        assertEquals(1, tools.size());
        assertEquals("{\"schema\":\"v1\"}", tools.get(0).inputSchema());

        // Verify cache: second call does not hit registry/provider again
        bridge.getExposedTools();
        Mockito.verify(mockRegistry, Mockito.times(1)).findTool(ToolCapability.GITHUB.toolName());
        Mockito.verify(mockSchemaProvider, Mockito.times(1)).getJsonSchema(mockTool);
    }

    @Test
    void testMatchingUsesToolNameNotEnumName() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of(ToolCapability.RECALL_MEMORY_SEARCH.toolName()));
        AgentTool mockTool = mock(AgentTool.class);
        when(mockRegistry.findTool(anyString())).thenReturn(Optional.of(mockTool));
        List<McpToolDescriptor> tools = bridge.getExposedTools();
        assertEquals(1, tools.size());
        assertEquals(
                ToolCapability.RECALL_MEMORY_SEARCH.toolName(),
                tools.get(0).name()); // must NOT be "RECALL_MEMORY_SEARCH"
    }

    @Test
    void testEntryWithNoMatchingToolName() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of("invalid_tool", ToolCapability.GITHUB.toolName()));
        AgentTool mockTool = mock(AgentTool.class);
        when(mockRegistry.findTool(anyString())).thenReturn(Optional.of(mockTool));

        List<McpToolDescriptor> tools = bridge.getExposedTools();
        assertEquals(1, tools.size());
        assertEquals(ToolCapability.GITHUB.toolName(), tools.get(0).name());
    }

    @Test
    void testInvokeAllowlistedTool() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of(ToolCapability.GITHUB.toolName()));
        when(mockRegistry.invokeTool(eq(ToolCapability.GITHUB.toolName()), eq("{}"), anyString()))
                .thenReturn(ToolResponse.success("ok"));

        String result = bridge.executeTool(new McpToolRequest(ToolCapability.GITHUB.toolName(), "{}", null));
        assertEquals("ok", result);
    }

    @Test
    void testInvokeNonAllowlistedTool() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of(ToolCapability.RECALL_MEMORY_SEARCH.toolName()));

        assertThrows(
                McpToolNotAllowedException.class,
                () -> bridge.executeTool(new McpToolRequest(ToolCapability.GITHUB.toolName(), "{}", null)));
    }

    @Test
    void testAuditRecordsHaveDistinctIdentities() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of(ToolCapability.GITHUB.toolName()));
        when(mockRegistry.invokeTool(eq(ToolCapability.GITHUB.toolName()), eq("{}"), anyString()))
                .thenReturn(ToolResponse.success("ok"));

        // Client A
        McpToolRequest reqA = new McpToolRequest(
                ToolCapability.GITHUB.toolName(),
                "{}",
                HttpServerRequest.class.cast(Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class[] {HttpServerRequest.class}, (p, m, args) -> {
                            if ("headers".equals(m.getName()))
                                return MultiMap.caseInsensitiveMultiMap()
                                        .add("X-MCP-Client-ID", "A")
                                        .add("X-MCP-Session-ID", "1");
                            return null;
                        })));
        bridge.executeTool(reqA);

        // Client B
        McpToolRequest reqB = new McpToolRequest(
                ToolCapability.GITHUB.toolName(),
                "{}",
                HttpServerRequest.class.cast(Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class[] {HttpServerRequest.class}, (p, m, args) -> {
                            if ("headers".equals(m.getName()))
                                return MultiMap.caseInsensitiveMultiMap()
                                        .add("X-MCP-Client-ID", "B")
                                        .add("X-MCP-Session-ID", "2");
                            return null;
                        })));
        bridge.executeTool(reqB);

        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);

        Mockito.verify(bridge.auditService, Mockito.times(2))
                .record(
                        sessionCaptor.capture(),
                        userCaptor.capture(),
                        anyString(),
                        eq(SecurityEventType.TOOL_INVOKED),
                        detailCaptor.capture());

        assertEquals("mcp-A-1", sessionCaptor.getAllValues().get(0));
        assertEquals("mcp:A", userCaptor.getAllValues().get(0));
        assertEquals("mcp-B-2", sessionCaptor.getAllValues().get(1));
        assertEquals("mcp:B", userCaptor.getAllValues().get(1));
    }

    @Test
    void testRoleMappingFromCapabilities() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of(ToolCapability.GITHUB.toolName()));
        when(mockRegistry.invokeTool(eq(ToolCapability.GITHUB.toolName()), eq("{}"), anyString()))
                .thenReturn(ToolResponse.success("ok"));

        McpToolRequest reqCoder = new McpToolRequest(
                ToolCapability.GITHUB.toolName(),
                "{}",
                HttpServerRequest.class.cast(Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class[] {HttpServerRequest.class}, (p, m, args) -> {
                            if ("headers".equals(m.getName()))
                                return MultiMap.caseInsensitiveMultiMap().add("X-MCP-Capabilities", "code_generation");
                            return null;
                        })));
        bridge.executeTool(reqCoder);

        ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(bridge.auditService)
                .record(
                        anyString(),
                        anyString(),
                        roleCaptor.capture(),
                        eq(SecurityEventType.TOOL_INVOKED),
                        anyString());

        assertEquals("coder", roleCaptor.getValue());
    }

    @Test
    void testWarmSchemaCacheThrowsOnMissingTool() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of("missing_tool"));
        when(mockRegistry.findTool("missing_tool")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> bridge.warmSchemaCache());
    }
}
