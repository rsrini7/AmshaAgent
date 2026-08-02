package com.amsha.mcp.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.tool.DynamicToolAdmission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DynamicToolLookupImplTest {

    private DynamicToolLookupImpl lookup;
    private DynamicToolCapabilityRegistry registry;

    @BeforeEach
    void setUp() {
        lookup = new DynamicToolLookupImpl();
        registry = new DynamicToolCapabilityRegistry();
        lookup.admissionLookup = toolName -> registry.find(toolName)
                .map(tool -> new DynamicToolAdmission(
                        tool.toolName(), tool.category(), tool.requiresApproval(), tool.requiredOperation()));
    }

    @Test
    void testRegisteredTool() {
        registry.register(
                new DynamicToolCapability("search_repos", "desc", "server", McpTransportType.STDIO, false, "{}"));
        assertTrue(lookup.isDynamicToolAllowed("search_repos"));
    }

    @Test
    void testUnregisteredTool() {
        assertFalse(lookup.isDynamicToolAllowed("unregistered_tool"));
    }

    @Test
    void testToolInAllowlistButNotInRegistry() {
        // The implementation doesn't check McpToolAllowlist, only DynamicToolCapabilityRegistry
        assertFalse(lookup.isDynamicToolAllowed(ToolCapability.GITHUB.toolName()));
        assertFalse(lookup.isDynamicToolAllowed(ToolCapability.RECALL_MEMORY_SEARCH.toolName()));
    }
}
