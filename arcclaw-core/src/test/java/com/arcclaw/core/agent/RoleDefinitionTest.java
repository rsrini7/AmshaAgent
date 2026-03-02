package com.arcclaw.core.agent;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class RoleDefinitionTest {

    @Test
    void testConsistencyAcrossSources() {
        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            RoleDefinition definition = role.definition();
            assertNotNull(definition, "Role " + role + " must have a definition");
            assertEquals(role.toScopedName(), definition.canonicalName());
            assertNotNull(definition.displayName());
            assertFalse(definition.displayName().isBlank());
        }
    }

    @Test
    void testToolNamesNeverNull() {
        RoleDefinition def = new RoleDefinition("test", "Test", null, 1, false, null);
        assertNotNull(def.capabilities(), "Capabilities should default to empty set");
        assertNotNull(def.toolNames(), "Tool names should default to empty set");
        assertTrue(def.toolNames().isEmpty());
    }

    @Test
    void testDeepImmutability() {
        Set<ToolCapability> mutableSet = new HashSet<>();
        mutableSet.add(ToolCapability.GITHUB);

        RoleDefinition def = new RoleDefinition("test", "Test", "Desc", 1, false, mutableSet);

        // Modifying original set does not affect record
        mutableSet.add(ToolCapability.MEMORY_SEARCH);
        assertEquals(1, def.capabilities().size());
        assertTrue(def.capabilities().contains(ToolCapability.GITHUB));

        // Returned sets are unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> def.capabilities().add(ToolCapability.SCHEDULE_TASK));
        assertThrows(UnsupportedOperationException.class, () -> def.toolNames().add("hacked"));
    }

    @Test
    void testBrahmaHasNoTools() {
        RoleDefinition coderDef = AgentRoleEnum.CODER.definition();
        assertTrue(coderDef.capabilities().isEmpty(), "Coder must have no tools per Phase 47 constraints");
        assertTrue(coderDef.toolNames().isEmpty());
    }

    @Test
    void testArchitectCapabilities() {
        RoleDefinition archDef = AgentRoleEnum.ARCHITECT.definition();
        assertEquals(Set.of(ToolCapability.GITHUB, ToolCapability.MEMORY_SEARCH), archDef.capabilities());
        Set<String> expectedTools = Set.of("github", "memory_search");
        assertEquals(expectedTools, archDef.toolNames(), "Architect should have github and memory_search tools");
    }

    @Test
    void testDeityAliases() {
        assertEquals(AgentRoleEnum.ARCHITECT, AgentRoleEnum.fromString("brahaspati"));
        assertEquals(AgentRoleEnum.COORDINATOR, AgentRoleEnum.fromString("vishnu"));
        assertEquals(AgentRoleEnum.RESEARCHER, AgentRoleEnum.fromString("saraswati"));
        assertEquals(AgentRoleEnum.CODER, AgentRoleEnum.fromString("brahma"));
        assertEquals(AgentRoleEnum.DEBUGGER, AgentRoleEnum.fromString("shiva"));
    }
}
