package com.arcclaw.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SkillReflexTest {

    private SkillRegistry mockRegistry;
    private SkillReflex reflex;

    @BeforeEach
    void setup() {
        mockRegistry = new SkillRegistry();
        reflex = new SkillReflex();
        reflex.registry = mockRegistry;
    }

    @Test
    void testReturnsFalseForNullInput() {
        assertFalse(reflex.matches(null));
    }

    @Test
    void testReturnsFalseForNonCommand() {
        assertFalse(reflex.matches("Hello"));
        assertFalse(reflex.matches("/status"));
    }

    @Test
    void testMatchesCommandWithWhitespaceAndCase() {
        mockRegistry.reload(List.of());
        assertTrue(reflex.matches("/skills"));
        assertTrue(reflex.matches("  /skills  "));
        assertTrue(reflex.matches("/SKILLS"));
    }

    @Test
    void testEmptyRegistryOutput() {
        mockRegistry.reload(List.of());
        String result = reflex.execute("session1", "user1", "chat1", "/skills");
        assertEquals("No skills are currently loaded.", result);
    }

    @Test
    void testPopulatedRegistryOutput() {
        SkillDefinition def1 = new SkillDefinition("id1", "reviewer", "Reviews PRs", List.of("pattern"), List.of(), 5, "prompt");
        com.arcclaw.core.agent.role.RoleDefinition role1 = new com.arcclaw.core.agent.role.RoleDefinition(
            "skill:reviewer", "Reviewer", "Reviews PRs", 5, false, java.util.Set.of()
        );
        CompiledSkillEntry e1 = new CompiledSkillEntry(def1, new Pattern[]{Pattern.compile("pattern")}, role1, "db");

        mockRegistry.reload(List.of(e1));
        String result = reflex.execute("session1", "user1", "chat1", "/skills");
        assertTrue(result.contains("### Active Skills"));
        assertTrue(result.contains("- **reviewer**: Reviews PRs (Routes: 1)"));
    }

    @Test
    void testBlankDescriptionFallback() {
        SkillDefinition def1 = new SkillDefinition("id1", "minimal", "", List.of(), List.of(), 5, "prompt");
        com.arcclaw.core.agent.role.RoleDefinition role1 = new com.arcclaw.core.agent.role.RoleDefinition(
            "skill:minimal", "Minimal", "", 5, false, java.util.Set.of()
        );
        CompiledSkillEntry e1 = new CompiledSkillEntry(def1, new Pattern[0], role1, "db");

        mockRegistry.reload(List.of(e1));
        String result = reflex.execute("session1", "user1", "chat1", "/skills");
        assertTrue(result.contains("- **minimal**: No description (Routes: 0)"));
    }
}
