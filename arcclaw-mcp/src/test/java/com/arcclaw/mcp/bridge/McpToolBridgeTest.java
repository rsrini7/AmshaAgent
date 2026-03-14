package com.arcclaw.mcp.bridge;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.mcp.config.McpToolAllowlist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpToolBridgeTest {

    private McpToolBridge bridge;
    private ToolRegistry mockRegistry;
    private McpToolAllowlist mockAllowlist;

    @BeforeEach
    void setUp() {
        bridge = new McpToolBridge();
        mockRegistry = mock(ToolRegistry.class);
        mockAllowlist = mock(McpToolAllowlist.class);
        bridge.toolRegistry = mockRegistry;
        bridge.allowlist = mockAllowlist;
    }

    @Test
    void testGetExposedTools() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of("github", "memory_search"));
        
        List<McpToolDescriptor> tools = bridge.getExposedTools();
        assertEquals(2, tools.size());
        assertEquals("github", tools.get(0).name());
        assertEquals("memory_search", tools.get(1).name());
    }

    @Test
    void testMatchingUsesToolNameNotEnumName() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of("memory_search"));
        List<com.arcclaw.mcp.bridge.McpToolDescriptor> tools = bridge.getExposedTools();
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
            
        String result = bridge.invoke("github", "{}");
        assertEquals("ok", result);
    }

    @Test
    void testInvokeNonAllowlistedTool() {
        when(mockAllowlist.exposedTools()).thenReturn(List.of("memory_search"));
        
        assertThrows(McpToolNotAllowedException.class, () -> bridge.invoke("github", "{}"));
    }
}
