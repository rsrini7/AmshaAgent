package com.arcclaw.core.agent.role;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoleRegistryTest {

    private RoleRegistry registry;

    @BeforeEach
    void setup() {
        registry = new RoleRegistry();
    }

    @Test
    void testDynamicRoleRegistrationAndRetrieval() {
        RoleDefinition def = new RoleDefinition(
                "skill:reviewer",
                "Reviewer",
                "Reviews code",
                5,
                false,
                Set.of(ToolCapability.GITHUB)
        );

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
                Set.of(ToolCapability.GITHUB, ToolCapability.MEMORY_SEARCH)
        );
        registry.registerDynamic("reviewer", def);

        Set<String> tools = registry.permittedToolsForDynamic("skill:reviewer");
        assertEquals(2, tools.size());
        assertTrue(tools.contains("github"));
        assertTrue(tools.contains("memory_search"));
    }

    @Test
    void testPermittedToolsForDynamicReturnsEmptyForUnknown() {
        Set<String> tools = registry.permittedToolsForDynamic("skill:unknown");
        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    void testDynamicRolesCollectionIsUnmodifiable() {
        RoleDefinition def = new RoleDefinition("skill:foo", "Foo", "", 5, false, Set.of());
        registry.registerDynamic("foo", def);

        var roles = registry.getDynamicRoles();
        assertThrows(UnsupportedOperationException.class, () -> roles.clear());
    }
}
