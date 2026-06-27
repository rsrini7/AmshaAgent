package com.amsha.core.agent.role;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentGroupRegistryTest {

    private AgentGroupRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentGroupRegistry();
    }

    @Test
    void testBuiltInGroupsExist() {
        Optional<AgentGroup> kailasa = registry.findById("kailasa");
        assertTrue(kailasa.isPresent());
        assertEquals("debugger", kailasa.get().leadRoleId());

        Optional<AgentGroup> brahmaloka = registry.findById("brahmaloka");
        assertTrue(brahmaloka.isPresent());
        assertEquals("coder", brahmaloka.get().leadRoleId());
    }

    @Test
    void testFindByLead() {
        Optional<AgentGroup> group = registry.findByLead("debugger");
        assertTrue(group.isPresent());
        assertEquals("kailasa", group.get().groupId());

        Optional<AgentGroup> unknown = registry.findByLead("unknown");
        assertFalse(unknown.isPresent());
    }

    @Test
    void testFindByMember() {
        Optional<AgentGroup> group = registry.findByMember("optimizer");
        assertTrue(group.isPresent());
        assertEquals("kailasa", group.get().groupId());

        Optional<AgentGroup> unknown = registry.findByMember("unknown");
        assertFalse(unknown.isPresent());
    }

    @Test
    void testAreInSameGroup() {
        assertTrue(registry.areInSameGroup("debugger", "optimizer"));
        assertFalse(registry.areInSameGroup("debugger", "coder"));
    }

    @Test
    void testGetSiblings() {
        List<String> siblings = registry.getSiblings("optimizer");
        assertTrue(siblings.contains("debugger"));
        assertFalse(siblings.contains("optimizer"));
        assertFalse(siblings.contains("coder"));
    }

    @Test
    void testDynamicGroupRegistration() {
        AgentGroup dynamic = new AgentGroup(
                "custom", "Custom Group", "healer", List.of("healer", "nurturer"), "Support domain", "No hint");

        registry.registerDynamic(dynamic);

        Optional<AgentGroup> found = registry.findById("custom");
        assertTrue(found.isPresent());
        assertEquals("healer", found.get().leadRoleId());

        Optional<AgentGroup> byLead = registry.findByLead("healer");
        // Vaikunta has healer as member, not lead, so dynamic lead healer should be found
        assertTrue(byLead.isPresent());
        assertEquals("custom", byLead.get().groupId());

        // Note: findByMember will find "nurturer" in kailasa first because built-in is checked first,
        // but let's test a unique dynamic member.
    }
}
