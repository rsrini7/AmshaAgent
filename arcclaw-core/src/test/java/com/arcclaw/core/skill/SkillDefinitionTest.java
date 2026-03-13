package com.arcclaw.core.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillDefinitionTest {

    @Test
    void testNullNameThrows() {
        assertThrows(NullPointerException.class, () ->
            new SkillDefinition("id", null, "desc", List.of(), List.of(), 5, "systemPrompt")
        );
    }

    @Test
    void testNullSystemPromptThrows() {
        assertThrows(NullPointerException.class, () ->
            new SkillDefinition("id", "code-reviewer", "desc", List.of(), List.of(), 5, null)
        );
    }

    @Test
    void testNullListsBecomeEmpty() {
        SkillDefinition def = new SkillDefinition("id", "code-reviewer", "desc", null, null, 5, "prompt");
        assertNotNull(def.triggerPatterns());
        assertTrue(def.triggerPatterns().isEmpty());
        assertNotNull(def.toolCapabilities());
        assertTrue(def.toolCapabilities().isEmpty());
    }

    @Test
    void testMaxIterationsDefaultsTo5IfZeroOrNegative() {
        SkillDefinition def1 = new SkillDefinition("id", "code-reviewer", "desc", List.of(), List.of(), 0, "prompt");
        assertEquals(5, def1.maxIterations());

        SkillDefinition def2 = new SkillDefinition("id", "code-reviewer", "desc", List.of(), List.of(), -10, "prompt");
        assertEquals(5, def2.maxIterations());

        SkillDefinition def3 = new SkillDefinition("id", "code-reviewer", "desc", List.of(), List.of(), 10, "prompt");
        assertEquals(10, def3.maxIterations());
    }

    @Test
    void testNullOrBlankIdGeneratesUUID() {
        SkillDefinition def1 = new SkillDefinition(null, "code-reviewer", "desc", List.of(), List.of(), 5, "prompt");
        assertNotNull(def1.id());
        assertFalse(def1.id().isBlank());

        SkillDefinition def2 = new SkillDefinition("   ", "code-reviewer", "desc", List.of(), List.of(), 5, "prompt");
        assertNotNull(def2.id());
        assertFalse(def2.id().isBlank());
    }

    @Test
    void testNullDescriptionBecomesEmptyString() {
        SkillDefinition def = new SkillDefinition("id", "code-reviewer", null, List.of(), List.of(), 5, "prompt");
        assertNotNull(def.description());
        assertEquals("", def.description());
    }
}
