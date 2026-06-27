package com.amsha.core.agent.role;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class AgentGroupTest {

    @Test
    void testValidConstruction() {
        AgentGroup group = new AgentGroup(
                "kailasa",
                "Kailasa",
                "debugger",
                List.of("debugger", "optimizer", "sentinel"),
                "Debugging and optimization",
                "Consult optimizer for recursion");

        assertEquals("kailasa", group.groupId());
        assertEquals("debugger", group.leadRoleId());
        assertEquals(3, group.memberRoleIds().size());
        assertTrue(group.contains("optimizer"));
        assertFalse(group.contains("coder"));
        assertTrue(group.isLead("debugger"));
        assertFalse(group.isLead("optimizer"));

        List<String> consultable = group.consultableMembers();
        assertEquals(2, consultable.size());
        assertFalse(consultable.contains("debugger"));
        assertTrue(consultable.contains("optimizer"));
        assertTrue(consultable.contains("sentinel"));
    }

    @Test
    void testNullOrBlankValidation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentGroup(null, "Kailasa", "debugger", List.of("debugger"), "domain", "hint"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentGroup("kailasa", "Kailasa", "", List.of("debugger"), "domain", "hint"));
    }

    @Test
    void testLeadMustBeAMemberValidation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentGroup("kailasa", "Kailasa", "debugger", List.of("optimizer"), "domain", "hint"));
    }

    @Test
    void testNullMemberListBecomesEmptyOrLeadOnly() {
        // Since leadRoleId must be a member, a null list must throw because lead won't be a member of the empty list
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentGroup("kailasa", "Kailasa", "debugger", null, "domain", "hint"));
    }
}
