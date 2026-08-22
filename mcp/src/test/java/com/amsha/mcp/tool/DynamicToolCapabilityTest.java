package com.amsha.mcp.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.amsha.core.tools.ToolCategory;
import com.amsha.security.api.ToolOperation;

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
        assertEquals(ToolOperation.NETWORK_OUTBOUND, capability.requiredOperation());
    }

    @Test
    void testExplicitCategoryAndOperation() {
        DynamicToolCapability capability = new DynamicToolCapability(
                "query",
                "desc",
                "server",
                McpTransportType.HTTP_SSE,
                false,
                "{}",
                ToolCategory.DATABASE,
                ToolOperation.WRITE_MEMORY);

        assertEquals(ToolCategory.DATABASE, capability.category());
        assertEquals(ToolOperation.WRITE_MEMORY, capability.requiredOperation());
    }

    @Test
    void databaseCategoryWithoutExplicitOperationRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DynamicToolCapability(
                        "query", "desc", "server", McpTransportType.HTTP_SSE, false, "{}", ToolCategory.DATABASE));
    }

    @Test
    void computeCategoryDefaultsToExecuteShell() {
        DynamicToolCapability capability = new DynamicToolCapability(
                "run", "desc", "server", McpTransportType.STDIO, false, "{}", ToolCategory.COMPUTE);
        assertEquals(ToolOperation.EXECUTE_SHELL, capability.requiredOperation());
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
    void oversizedDescriptionIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DynamicToolCapability(
                        "tool",
                        "x".repeat(DynamicToolCapability.MAX_DESCRIPTION_LENGTH + 1),
                        "server",
                        McpTransportType.STDIO,
                        false,
                        "{}"));
    }

    @Test
    void oversizedSchemaIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DynamicToolCapability(
                        "tool",
                        "desc",
                        "server",
                        McpTransportType.STDIO,
                        false,
                        "{\"description\":\"" + "x".repeat(DynamicToolCapability.MAX_INPUT_SCHEMA_LENGTH) + "\"}"));
    }

    @Test
    void schemaMustBeValidObjectJson() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DynamicToolCapability("tool", "desc", "server", McpTransportType.STDIO, false, "[]"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DynamicToolCapability("tool", "desc", "server", McpTransportType.STDIO, false, "not-json"));
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
                () -> new DynamicToolCapability(
                        "tool",
                        "desc",
                        "server",
                        McpTransportType.STDIO,
                        false,
                        "{}",
                        null,
                        ToolOperation.NETWORK_OUTBOUND));
    }

    @Test
    void testNullRequiredOperation() {
        assertThrows(
                NullPointerException.class,
                () -> new DynamicToolCapability(
                        "tool", "desc", "server", McpTransportType.STDIO, false, "{}", ToolCategory.NETWORK, null));
    }
}
