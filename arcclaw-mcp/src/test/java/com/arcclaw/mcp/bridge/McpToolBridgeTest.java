package com.arcclaw.mcp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.util.List;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.mcp.config.McpToolAllowlist;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class McpToolBridgeTest {

    private McpToolBridge bridge;
    private ToolRegistry mockRegistry;
    private McpToolAllowlist mockAllowlist;
    private ObjectMapper mockObjectMapper;

    @BeforeEach
    void setUp() {
        bridge = new McpToolBridge();
        mockRegistry = mock(ToolRegistry.class);
        mockAllowlist = mock(McpToolAllowlist.class);
        mockObjectMapper = mock(ObjectMapper.class);
        bridge.toolRegistry = mockRegistry;
        bridge.allowlist = mockAllowlist;
        bridge.objectMapper = mockObjectMapper;
        bridge.auditService = mock(AuditService.class);
        bridge.clearCache();
    }

    @Test
    void testGetExposedTools() throws Exception {
        when(mockAllowlist.exposedTools()).thenReturn(List.of("github", "memory_search"));
        AgentTool mockTool = mock(AgentTool.class);
        when(mockRegistry.findTool(anyString())).thenReturn(java.util.Optional.of(mockTool));
        when(mockObjectMapper.writeValueAsString(org.mockito.ArgumentMatchers.any())).thenReturn("{}");
        
        List<McpToolDescriptor> tools = bridge.getExposedTools();
        assertEquals(2, tools.size());
        assertEquals("github", tools.get(0).name());
        assertEquals("memory_search", tools.get(1).name());
    }

    @Test
    void testGetExposedToolsSchemaDerivation() throws Exception {
        when(mockAllowlist.exposedTools()).thenReturn(List.of("github"));
        AgentTool mockTool = mock(AgentTool.class);
        JsonObjectSchema schema = JsonObjectSchema.builder().build();
        when(mockTool.parametersSchema()).thenReturn(schema);
        when(mockRegistry.findTool("github")).thenReturn(java.util.Optional.of(mockTool));
        when(mockObjectMapper.writeValueAsString(schema)).thenReturn("{\"schema\":\"v1\"}");

        List<McpToolDescriptor> tools = bridge.getExposedTools();
        assertEquals(1, tools.size());
        assertEquals("{\"schema\":\"v1\"}", tools.get(0).inputSchema());

        // Verify cache: second call does not hit registry/mapper again
        bridge.getExposedTools();
        Mockito.verify(mockRegistry, Mockito.times(1)).findTool("github");
        Mockito.verify(mockObjectMapper, Mockito.times(1)).writeValueAsString(schema);
    }

    @Test
    void testMatchingUsesToolNameNotEnumName() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of("memory_search"));
        List<McpToolDescriptor> tools = bridge.getExposedTools();
        assertEquals(1, tools.size());
        assertEquals("memory_search", tools.get(0).name()); // must NOT be "MEMORY_SEARCH"
    }

    @Test
    void testEntryWithNoMatchingToolName() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of("invalid_tool", "github"));
        
        List<McpToolDescriptor> tools = bridge.getExposedTools();
        assertEquals(1, tools.size());
        assertEquals("github", tools.get(0).name());
    }

    @Test
    void testInvokeAllowlistedTool() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of("github"));
        when(mockRegistry.invokeTool(eq("github"), eq("{}"), anyString()))
            .thenReturn(ToolResponse.success("ok"));
            
        String result = bridge.executeTool(new McpToolRequest("github", "{}", null));
        assertEquals("ok", result);
    }

    @Test
    void testInvokeNonAllowlistedTool() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of("memory_search"));
        
        assertThrows(McpToolNotAllowedException.class, () -> bridge.executeTool(new McpToolRequest("github", "{}", null)));
    }

    @Test
    void testAuditRecordsHaveDistinctIdentities() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of("github"));
        when(mockRegistry.invokeTool(eq("github"), eq("{}"), anyString())).thenReturn(ToolResponse.success("ok"));

        // Client A
        McpToolRequest reqA = new McpToolRequest("github", "{}", HttpServerRequest.class.cast(
            Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{HttpServerRequest.class}, (p, m, args) -> {
                if ("headers".equals(m.getName())) return MultiMap.caseInsensitiveMultiMap().add("X-MCP-Client-ID", "A").add("X-MCP-Session-ID", "1");
                return null;
            })));
        bridge.executeTool(reqA);

        // Client B
        McpToolRequest reqB = new McpToolRequest("github", "{}", HttpServerRequest.class.cast(
            Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{HttpServerRequest.class}, (p, m, args) -> {
                if ("headers".equals(m.getName())) return MultiMap.caseInsensitiveMultiMap().add("X-MCP-Client-ID", "B").add("X-MCP-Session-ID", "2");
                return null;
            })));
        bridge.executeTool(reqB);

        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        
        Mockito.verify(bridge.auditService, Mockito.times(2)).record(
            sessionCaptor.capture(), userCaptor.capture(), anyString(), eq(SecurityEventType.TOOL_INVOKED), detailCaptor.capture());

        assertEquals("mcp-A-1", sessionCaptor.getAllValues().get(0));
        assertEquals("mcp:A", userCaptor.getAllValues().get(0));
        assertEquals("mcp-B-2", sessionCaptor.getAllValues().get(1));
        assertEquals("mcp:B", userCaptor.getAllValues().get(1));
    }

    @Test
    void testRoleMappingFromCapabilities() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of("github"));
        when(mockRegistry.invokeTool(eq("github"), eq("{}"), anyString())).thenReturn(ToolResponse.success("ok"));

        McpToolRequest reqCoder = new McpToolRequest("github", "{}", HttpServerRequest.class.cast(
            Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{HttpServerRequest.class}, (p, m, args) -> {
                if ("headers".equals(m.getName())) return MultiMap.caseInsensitiveMultiMap().add("X-MCP-Capabilities", "code_generation");
                return null;
            })));
        bridge.executeTool(reqCoder);

        ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(bridge.auditService).record(anyString(), anyString(), roleCaptor.capture(), eq(SecurityEventType.TOOL_INVOKED), anyString());
        
        assertEquals("coder", roleCaptor.getValue());
    }

    @Test
    void testWarmSchemaCacheThrowsOnMissingTool() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of("missing_tool"));
        when(mockRegistry.findTool("missing_tool")).thenReturn(java.util.Optional.empty());

        assertThrows(IllegalStateException.class, () -> bridge.warmSchemaCache());
    }
}
