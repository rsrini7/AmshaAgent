package com.arcclaw.mcp.tool;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DynamicToolLookupImplTest {

    private DynamicToolLookupImpl lookup;
    private DynamicToolCapabilityRegistry registry;

    @BeforeEach
    void setUp() {
        lookup = new DynamicToolLookupImpl();
        registry = new DynamicToolCapabilityRegistry();
        lookup.registry = registry;
    }

    @Test
    void testRegisteredTool() {
        registry.register(new DynamicToolCapability("search_repos", "desc", "server", McpTransportType.STDIO, false, "{}"));
        assertTrue(lookup.isDynamicToolAllowed("search_repos"));
    }

    @Test
    void testUnregisteredTool() {
        assertFalse(lookup.isDynamicToolAllowed("unregistered_tool"));
    }

    @Test
    void testToolInAllowlistButNotInRegistry() {
        // The implementation doesn't check McpToolAllowlist, only DynamicToolCapabilityRegistry
        assertFalse(lookup.isDynamicToolAllowed("github")); 
        assertFalse(lookup.isDynamicToolAllowed("memory_search"));
    }
}
