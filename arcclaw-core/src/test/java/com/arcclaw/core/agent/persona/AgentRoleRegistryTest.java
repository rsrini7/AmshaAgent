package com.arcclaw.core.agent.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AgentRoleRegistryTest {

    @Inject AgentRoleRegistry registry;

    @Test
    void allSixBuiltInRolesAreDiscoverable() {
        List<AgentRoleEnum> roles = registry.allRoles();
        assertTrue(roles.size() >= 6);
        var scopedNames = roles.stream().map(AgentRoleEnum::toScopedName).toList();
        assertTrue(scopedNames.contains("coder"));
        assertTrue(scopedNames.contains("debugger"));
        assertTrue(scopedNames.contains("researcher"));
        assertTrue(scopedNames.contains("coordinator"));
        assertTrue(scopedNames.contains("architect"));
        assertTrue(scopedNames.contains("synthesizer"));
    }

    @Test
    void resolveByNameReturnsCorrectAgent() {
        Optional<Agent> brahma = registry.resolveByName("coder");
        assertTrue(brahma.isPresent());
        assertEquals("coder", brahma.get().role());
    }

    @Test
    void unknownRoleNameThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> AgentRoleEnum.fromString("security-auditor"));
    }

    @Test
    void thirdPartyRoleThrowsUnlessRegistered() {
        assertThrows(IllegalArgumentException.class, () -> AgentRoleEnum.fromString("customer-support-agent"));
    }
}
