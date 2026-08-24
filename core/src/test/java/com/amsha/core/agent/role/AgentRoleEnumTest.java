package com.amsha.core.agent.role;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

public class AgentRoleEnumTest {

    @Test
    public void fromStringNullAndBlankDefaultToCoordinator() {
        assertEquals(AgentRoleEnum.COORDINATOR, AgentRoleEnum.fromString(null));
        assertEquals(AgentRoleEnum.COORDINATOR, AgentRoleEnum.fromString(""));
        assertEquals(AgentRoleEnum.COORDINATOR, AgentRoleEnum.fromString("   "));
    }

    @Test
    public void fromStringIsCaseInsensitiveAndTrims() {
        assertEquals(AgentRoleEnum.RESEARCHER, AgentRoleEnum.fromString("RESEARCHER"));
        assertEquals(AgentRoleEnum.CODER, AgentRoleEnum.fromString("  Coder  "));
        assertEquals(AgentRoleEnum.SYNTHESIZER, AgentRoleEnum.fromString("Synthesizer".toUpperCase(Locale.ROOT)));
    }

    @Test
    public void unknownBuiltInRoleThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentRoleEnum.fromString("wizard"));
        assertThrows(IllegalArgumentException.class, () -> AgentRoleEnum.fromString("coordinatorx"));
    }

    @Test
    public void skillRolesRouteDynamically() {
        String skillRole = AgentRoleEnum.SKILL_PREFIX + "host_exec";
        assertTrue(AgentRoleEnum.isSkillRole(skillRole));
        assertTrue(AgentRoleEnum.isKnownRole(skillRole));
        AgentRoleEnum dynamic = AgentRoleEnum.fromString(skillRole);
        assertEquals(skillRole, dynamic.id());
        assertEquals(skillRole, dynamic.toScopedName());
        assertEquals("host_exec", AgentRoleEnum.skillName(skillRole));
        assertFalse(AgentRoleEnum.isSkillRole("coder"));
        assertThrows(IllegalArgumentException.class, () -> AgentRoleEnum.skillName("coder"));
        assertFalse(AgentRoleEnum.isSkillRole(null));
    }

    @Test
    public void isKnownRoleDiscriminatesCorrectly() {
        assertTrue(AgentRoleEnum.isKnownRole("architect"));
        assertTrue(AgentRoleEnum.isKnownRole("  DEBUGGER "));
        assertFalse(AgentRoleEnum.isKnownRole(null));
        assertFalse(AgentRoleEnum.isKnownRole("no-such-role"));
    }

    @Test
    public void toScopedNamePreservesId() {
        for (AgentRoleEnum role : new AgentRoleEnum[] {
            AgentRoleEnum.ARCHITECT, AgentRoleEnum.COORDINATOR, AgentRoleEnum.OPTIMIZER, AgentRoleEnum.NURTURER
        }) {
            assertEquals(role.id(), role.toScopedName());
        }
    }

    @Test
    public void definitionFallsBackForDynamicRoles() {
        RoleDefinition builtinDef = AgentRoleEnum.CODER.definition();
        assertEquals(AgentRoleEnum.CODER_ID, builtinDef.canonicalName());

        AgentRoleEnum dynamic = AgentRoleEnum.fromString(AgentRoleEnum.SKILL_PREFIX + "report_gen");
        RoleDefinition fallback = dynamic.definition();
        assertEquals("Dynamic Role", fallback.displayName());
        assertEquals(dynamic.id(), fallback.canonicalName());
    }

    @Test
    public void builtInRolesHavePersonas() {
        for (AgentRoleEnum role :
                new AgentRoleEnum[] {AgentRoleEnum.RESEARCHER, AgentRoleEnum.CODER, AgentRoleEnum.SYNTHESIZER}) {
            assertTrue(role.persona().isPresent(), "built-in role should map to a persona: " + role.id());
        }
    }
}
