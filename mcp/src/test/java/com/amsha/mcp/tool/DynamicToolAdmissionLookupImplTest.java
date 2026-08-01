package com.amsha.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import com.amsha.core.security.ToolOperation;
import com.amsha.core.tool.DynamicToolAdmission;
import com.amsha.core.tools.ToolCategory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DynamicToolAdmissionLookupImplTest {

    private DynamicToolCapabilityRegistry registry;
    private DynamicToolAdmissionLookupImpl lookup;

    @BeforeEach
    void setUp() {
        registry = new DynamicToolCapabilityRegistry();
        lookup = new DynamicToolAdmissionLookupImpl();
        lookup.registry = registry;
    }

    @Test
    void findAdmissionProjectsRegisteredCapabilityAtomically() {
        registry.register(new DynamicToolCapability(
                "search_repos",
                "desc",
                "server",
                McpTransportType.STDIO,
                true,
                "{}",
                ToolCategory.NETWORK,
                ToolOperation.NETWORK_OUTBOUND));

        Optional<DynamicToolAdmission> admission = lookup.findAdmission("search_repos");
        assertTrue(admission.isPresent());
        assertEquals("search_repos", admission.get().toolName());
        assertEquals(ToolCategory.NETWORK, admission.get().category());
        assertTrue(admission.get().requiresApproval());
        assertEquals(ToolOperation.NETWORK_OUTBOUND, admission.get().requiredOperation());
    }

    @Test
    void registerReplacesAdmissionMetadataAtomically() {
        registry.register(
                new DynamicToolCapability("tool", "d", "s", McpTransportType.STDIO, false, "{}", ToolCategory.NETWORK));
        registry.register(new DynamicToolCapability(
                "tool",
                "d2",
                "s",
                McpTransportType.STDIO,
                true,
                "{}",
                ToolCategory.COMPUTE,
                ToolOperation.EXECUTE_SHELL));

        DynamicToolAdmission admission = lookup.findAdmission("tool").orElseThrow();
        assertEquals(ToolCategory.COMPUTE, admission.category());
        assertTrue(admission.requiresApproval());
        assertEquals(ToolOperation.EXECUTE_SHELL, admission.requiredOperation());
    }

    @Test
    void deregisterClearsAdmission() {
        registry.register(
                new DynamicToolCapability("tool", "d", "s", McpTransportType.STDIO, false, "{}", ToolCategory.NETWORK));
        registry.deregister("tool");
        assertTrue(lookup.findAdmission("tool").isEmpty());
    }
}
