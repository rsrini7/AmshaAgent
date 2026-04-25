package com.arcclaw.core.agent.role;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

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
        RoleDefinition def = new RoleDefinition("test", "Test", null, 1, false, false, null, null);
        assertNotNull(def.capabilities(), "Capabilities should default to empty set");
        assertNotNull(def.toolNames(), "Tool names should default to empty set");
        assertTrue(def.toolNames().isEmpty());
    }

    @Test
    void testDeepImmutability() {
        Set<ToolCapability> mutableSet = new HashSet<>();
        mutableSet.add(ToolCapability.GITHUB);

        RoleDefinition def = new RoleDefinition("test", "Test", "Desc", 1, false, false, mutableSet, null);

        // Modifying original set does not affect record
        mutableSet.add(ToolCapability.MEMORY_SEARCH);
        assertEquals(1, def.capabilities().size());
        assertTrue(def.capabilities().contains(ToolCapability.GITHUB));

        // Returned sets are unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> def.capabilities().add(ToolCapability.SCHEDULE_TASK));
        assertThrows(UnsupportedOperationException.class, () -> def.toolNames().add("hacked"));
    }

    @Test
    void testCanGenerateCode() {
        assertTrue(AgentRoleEnum.CODER.definition().canGenerateCode());
        assertTrue(AgentRoleEnum.DEBUGGER.definition().canGenerateCode());
        assertFalse(AgentRoleEnum.ARCHITECT.definition().canGenerateCode());
        assertFalse(AgentRoleEnum.RESEARCHER.definition().canGenerateCode());
        assertFalse(AgentRoleEnum.COORDINATOR.definition().canGenerateCode());
        assertFalse(AgentRoleEnum.SYNTHESIZER.definition().canGenerateCode());
    }

    @Test
    void testBrahmaHasSandboxExecAndDelegate() {
        Set<ToolCapability> caps = AgentRoleEnum.CODER.definition().capabilities();
        assertEquals(2, caps.size(), "Coder must have exactly two capabilities: SANDBOX_EXEC and DELEGATE_TO_AGENT");
        assertTrue(caps.contains(ToolCapability.SANDBOX_EXEC));
        assertTrue(caps.contains(ToolCapability.DELEGATE_TO_AGENT));
    }

    @Test
    void testArchitectCapabilities() {
        RoleDefinition archDef = AgentRoleEnum.ARCHITECT.definition();
        assertEquals(Set.of(ToolCapability.GITHUB, ToolCapability.MEMORY_SEARCH, ToolCapability.DELEGATE_TO_AGENT), archDef.capabilities());
        Set<String> expectedTools = Set.of(ToolCapability.GITHUB.toolName(), ToolCapability.MEMORY_SEARCH.toolName(), ToolCapability.DELEGATE_TO_AGENT.toolName());
        assertEquals(expectedTools, archDef.toolNames(), "Architect should have github, memory_search and delegate_to_agent tools");
    }

    @Test
    void testDeityAliases() {
        assertEquals(AgentRoleEnum.ARCHITECT, AgentRoleEnum.fromString("brahaspati"));
        assertEquals(AgentRoleEnum.ARCHITECT, AgentRoleEnum.fromString("brihaspati"));
        assertEquals(AgentRoleEnum.COORDINATOR, AgentRoleEnum.fromString("vishnu"));
        assertEquals(AgentRoleEnum.RESEARCHER, AgentRoleEnum.fromString("saraswati"));
        assertEquals(AgentRoleEnum.CODER, AgentRoleEnum.fromString("brahma"));
        assertEquals(AgentRoleEnum.DEBUGGER, AgentRoleEnum.fromString("shiva"));
    }
}
