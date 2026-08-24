package com.amsha.core.agent.role;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;

import com.amsha.tool.api.ToolCapability;

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
        mutableSet.add(ToolCapability.RECALL_MEMORY_SEARCH);
        assertEquals(1, def.capabilities().size());
        assertTrue(def.capabilities().contains(ToolCapability.GITHUB));

        // Returned sets are unmodifiable
        assertThrows(
                UnsupportedOperationException.class, () -> def.capabilities().add(ToolCapability.SCHEDULE_TASK));
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
    void testDeityAliases() {
        assertEquals(AgentRoleEnum.ARCHITECT, AgentRoleEnum.fromString("brahaspati"));
        assertEquals(AgentRoleEnum.ARCHITECT, AgentRoleEnum.fromString("brihaspati"));
        assertEquals(AgentRoleEnum.COORDINATOR, AgentRoleEnum.fromString("vishnu"));
        assertEquals(AgentRoleEnum.RESEARCHER, AgentRoleEnum.fromString("saraswati"));
        assertEquals(AgentRoleEnum.CODER, AgentRoleEnum.fromString("brahma"));
        assertEquals(AgentRoleEnum.DEBUGGER, AgentRoleEnum.fromString("shiva"));
    }
}
