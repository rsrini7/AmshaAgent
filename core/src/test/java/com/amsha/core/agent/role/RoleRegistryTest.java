package com.amsha.core.agent.role;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import com.amsha.tool.api.ToolCapability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoleRegistryTest {

    private RoleRegistry registry;

    @BeforeEach
    void setup() {
        registry = new RoleRegistry();
    }

    @Test
    void testDynamicRoleRegistrationAndRetrieval() {
        RoleDefinition def = new RoleDefinition(
                "skill:reviewer", "Reviewer", "Reviews code", 5, false, false, Set.of(ToolCapability.GITHUB), Set.of());

        registry.registerDynamic("reviewer", def);

        assertEquals(1, registry.getDynamicRoles().size());
        assertTrue(registry.getDynamicRoles().contains(def));
    }

    @Test
    void testPermittedToolsForDynamicReturnsTools() {
        RoleDefinition def = new RoleDefinition(
                "skill:reviewer",
                "Reviewer",
                "Reviews code",
                5,
                false,
                false,
                Set.of(ToolCapability.GITHUB, ToolCapability.RECALL_MEMORY_SEARCH),
                Set.of());
        registry.registerDynamic("reviewer", def);

        Set<String> tools = registry.permittedToolsForDynamic("skill:reviewer");
        assertEquals(2, tools.size());
        assertTrue(tools.contains(ToolCapability.GITHUB.toolName()));
        assertTrue(tools.contains(ToolCapability.RECALL_MEMORY_SEARCH.toolName()));
    }

    @Test
    void testPermittedToolsForDynamicReturnsEmptyForUnknown() {
        Set<String> tools = registry.permittedToolsForDynamic("skill:unknown");
        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    void testDynamicRolesCollectionIsUnmodifiable() {
        RoleDefinition def = new RoleDefinition("skill:foo", "Foo", "", 5, false, false, Set.of(), Set.of());
        registry.registerDynamic("foo", def);

        var roles = registry.getDynamicRoles();
        assertThrows(UnsupportedOperationException.class, () -> roles.clear());
    }
}
