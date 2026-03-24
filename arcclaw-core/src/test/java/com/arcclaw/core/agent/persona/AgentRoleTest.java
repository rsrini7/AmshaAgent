package com.arcclaw.core.agent.persona;

import static org.junit.jupiter.api.Assertions.*;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.RoleRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AgentRoleTest {

    @Test
    void testFromStringCanonicalNames() {
        assertEquals(AgentRoleEnum.ARCHITECT, AgentRoleEnum.fromString("architect"));
        assertEquals(AgentRoleEnum.COORDINATOR, AgentRoleEnum.fromString("coordinator"));
        assertEquals(AgentRoleEnum.RESEARCHER, AgentRoleEnum.fromString("researcher"));
        assertEquals(AgentRoleEnum.CODER, AgentRoleEnum.fromString("coder"));
        assertEquals(AgentRoleEnum.DEBUGGER, AgentRoleEnum.fromString("debugger"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "vishnu,   COORDINATOR",
        "saraswati,RESEARCHER",
        "brahma,   CODER",
        "shiva,    DEBUGGER"
    })
    void testFromStringDeityAliases(String alias, String expectedName) {
        AgentRoleEnum expected = AgentRoleEnum.valueOf(expectedName);
        assertEquals(expected, AgentRoleEnum.fromString(alias));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "kali", "", " "})
    void testFromStringUnknownThrows(String bad) {
        assertThrows(IllegalArgumentException.class, () -> AgentRoleEnum.fromString(bad));
    }

    @Test
    void testFromStringNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentRoleEnum.fromString(null));
    }

    @Test
    void testToScopedNameRoundTrip() {
        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            String scopedName = role.toScopedName();
            assertTrue(scopedName.equals(scopedName.toLowerCase()));
            // round-trip: fromString(toScopedName()) should return same enum constant
            assertEquals(role, AgentRoleEnum.fromString(scopedName));
        }
    }

    @Test
    void testAllRolesHaveRoleRegistryEntry() {
        RoleRegistry registry = new RoleRegistry();
        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            // Must not throw and must return valid values
            int maxIt = registry.defaultMaxIterations(role);
            assertTrue(maxIt > 0);

            var tools = registry.permittedTools(role);
            assertNotNull(tools);

            // boolean — just calling, any value is acceptable
            registry.requiresApprovalForTools(role);
        }
    }
}
