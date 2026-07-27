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
        // Use roles that do not belong to any built-in group so registration is allowed.
        AgentGroup dynamic = new AgentGroup(
                "custom",
                "Custom Group",
                "skill:lead",
                List.of("skill:lead", "skill:member"),
                "Support domain",
                "No hint");

        registry.registerDynamic(dynamic);

        Optional<AgentGroup> found = registry.findById("custom");
        assertTrue(found.isPresent());
        assertEquals("skill:lead", found.get().leadRoleId());

        Optional<AgentGroup> byLead = registry.findByLead("skill:lead");
        assertTrue(byLead.isPresent());
        assertEquals("custom", byLead.get().groupId());
    }

    @Test
    void registerDynamic_rejectsDuplicateGroupId() {
        AgentGroup dynamic = new AgentGroup(
                "custom",
                "Custom Group",
                "skill:lead",
                List.of("skill:lead", "skill:member"),
                "Support domain",
                "No hint");
        registry.registerDynamic(dynamic);
        AgentGroup duplicate = new AgentGroup("custom", "Other", "skill:other", List.of("skill:other"), "x", "y");
        assertThrows(IllegalArgumentException.class, () -> registry.registerDynamic(duplicate));
        // State unchanged: the original registration remains.
        assertEquals("skill:lead", registry.findById("custom").get().leadRoleId());
    }

    @Test
    void registerDynamic_rejectsOverlapWithBuiltInGroup() {
        // "healer" belongs to the built-in Vaikunta group.
        AgentGroup overlapping = new AgentGroup("dyn-x", "X", "healer", List.of("healer", "skill:free"), "x", "y");
        assertThrows(IllegalArgumentException.class, () -> registry.registerDynamic(overlapping));
        assertFalse(registry.findById("dyn-x").isPresent());
    }

    @Test
    void registerDynamic_rejectsDuplicateMemberWithinGroup() {
        AgentGroup dupMember =
                new AgentGroup("dyn-y", "Y", "skill:a", List.of("skill:a", "skill:b", "skill:a"), "x", "y");
        assertThrows(IllegalArgumentException.class, () -> registry.registerDynamic(dupMember));
        assertFalse(registry.findById("dyn-y").isPresent());
    }
}
