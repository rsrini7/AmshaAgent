package com.amsha.mcp.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.amsha.core.tools.ToolCategory;

import org.junit.jupiter.api.Test;

class DynamicToolCapabilityTest {

    @Test
    void testValidConstruction() {
        DynamicToolCapability capability = new DynamicToolCapability(
                "search_repos", "Search repositories", "github-server", McpTransportType.STDIO, false, "{}");
        assertEquals("search_repos", capability.toolName());
        assertEquals("Search repositories", capability.description());
        assertEquals("github-server", capability.mcpServerName());
        assertEquals(McpTransportType.STDIO, capability.transport());
        assertFalse(capability.requiresApproval());
        assertEquals("{}", capability.inputSchema());
        assertEquals(ToolCategory.NETWORK, capability.category());
    }

    @Test
    void testExplicitCategory() {
        DynamicToolCapability capability = new DynamicToolCapability(
                "query", "desc", "server", McpTransportType.HTTP_SSE, false, "{}", ToolCategory.DATABASE);

        assertEquals(ToolCategory.DATABASE, capability.category());
    }

    @Test
    void testNullToolName() {
        assertThrows(
                NullPointerException.class,
                () -> new DynamicToolCapability(null, "desc", "server", McpTransportType.STDIO, false, "{}"));
    }

    @Test
    void testBlankToolName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DynamicToolCapability("   ", "desc", "server", McpTransportType.STDIO, false, "{}"));
    }

    @Test
    void testNullServerName() {
        assertThrows(
                NullPointerException.class,
                () -> new DynamicToolCapability("tool", "desc", null, McpTransportType.STDIO, false, "{}"));
    }

    @Test
    void testNullTransport() {
        assertThrows(
                NullPointerException.class,
                () -> new DynamicToolCapability("tool", "desc", "server", null, false, "{}"));
    }

    @Test
    void testNullCategory() {
        assertThrows(
                NullPointerException.class,
                () -> new DynamicToolCapability("tool", "desc", "server", McpTransportType.STDIO, false, "{}", null));
    }
}
