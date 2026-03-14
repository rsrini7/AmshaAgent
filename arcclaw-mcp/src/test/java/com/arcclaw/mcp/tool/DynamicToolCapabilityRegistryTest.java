package com.arcclaw.mcp.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Collection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DynamicToolCapabilityRegistryTest {

    private DynamicToolCapabilityRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DynamicToolCapabilityRegistry();
    }

    private DynamicToolCapability createTool(String name) {
        return new DynamicToolCapability(name, "desc", "server", McpTransportType.STDIO, false, "{}");
    }

    @Test
    void testRegisterAndFind() {
        DynamicToolCapability tool = createTool("test_tool");
        registry.register(tool);

        Optional<DynamicToolCapability> found = registry.find("test_tool");
        assertTrue(found.isPresent());
        assertEquals(tool, found.get());
        assertTrue(registry.isRegistered("test_tool"));
    }

    @Test
    void testFindUnknown() {
        Optional<DynamicToolCapability> found = registry.find("unknown_tool");
        assertTrue(found.isEmpty());
        assertFalse(registry.isRegistered("unknown_tool"));
    }

    @Test
    void testDeregister() {
        DynamicToolCapability tool = createTool("temp_tool");
        registry.register(tool);
        assertTrue(registry.isRegistered("temp_tool"));

        registry.deregister("temp_tool");
        assertFalse(registry.isRegistered("temp_tool"));
        assertTrue(registry.find("temp_tool").isEmpty());
    }

    @Test
    void testRegisterNull() {
        assertThrows(NullPointerException.class, () -> registry.register(null));
    }

    @Test
    void testGetAllReflectsCurrentState() {
        assertTrue(registry.getAll().isEmpty());

        registry.register(createTool("tool1"));
        registry.register(createTool("tool2"));

        Collection<DynamicToolCapability> all = registry.getAll();
        assertEquals(2, all.size());

        registry.deregister("tool1");
        Collection<DynamicToolCapability> allAfterDeregister = registry.getAll();
        assertEquals(1, allAfterDeregister.size());
    }
}
