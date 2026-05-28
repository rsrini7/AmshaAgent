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
        assertEquals(AgentRoleEnum.OPTIMIZER, AgentRoleEnum.fromString("optimizer"));
        assertEquals(AgentRoleEnum.SENTINEL, AgentRoleEnum.fromString("sentinel"));
        assertEquals(AgentRoleEnum.HEALER, AgentRoleEnum.fromString("healer"));
        assertEquals(AgentRoleEnum.PROTECTOR, AgentRoleEnum.fromString("protector"));
        assertEquals(AgentRoleEnum.PURIFIER, AgentRoleEnum.fromString("purifier"));
        assertEquals(AgentRoleEnum.NURTURER, AgentRoleEnum.fromString("nurturer"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "vishnu,   coordinator",
        "saraswati,researcher",
        "brahma,   coder",
        "shiva,    debugger",
        "ganesha,  optimizer",
        "kartikeya,sentinel",
        "lakshmi,  healer",
        "durga,    protector",
        "kali,     purifier",
        "parvati,  nurturer",
        "brihaspati,architect",
        "dattatreya,synthesizer"
    })
    void testFromStringDeityAliases(String alias, String expectedName) {
        AgentRoleEnum expected = AgentRoleEnum.fromString(expectedName);
        assertEquals(expected, AgentRoleEnum.fromString(alias));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "zeus"})
    void testFromStringUnknownThrows(String bad) {
        assertThrows(IllegalArgumentException.class, () -> AgentRoleEnum.fromString(bad));
    }

    @Test
    void testFromStringNullReturnsCoordinator() {
        assertEquals(AgentRoleEnum.COORDINATOR, AgentRoleEnum.fromString(null));
        assertEquals(AgentRoleEnum.COORDINATOR, AgentRoleEnum.fromString(" "));
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

            registry.requiresApprovalForTools(role);
        }
    }
}
