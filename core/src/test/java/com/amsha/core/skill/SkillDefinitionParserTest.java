package com.amsha.core.skill;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SkillDefinitionParserTest {

    private final SkillDefinitionParser parser = new SkillDefinitionParser();

    @Test
    void testValidTwoBlockYaml() {
        String doc = """
                ---
                name: code-reviewer
                description: Reviews PRs
                trigger-patterns:
                  - "review.*"
                tool-capabilities:
                  - GITHUB
                max-iterations: 8
                ---
                SYSTEM PROMPT INSTRUCTIONS
                """;

        SkillDefinition def = parser.parse(doc);
        assertEquals("code-reviewer", def.name());
        assertEquals("Reviews PRs", def.description());
        assertEquals(1, def.triggerPatterns().size());
        assertEquals("review.*", def.triggerPatterns().get(0));
        assertEquals(1, def.toolCapabilities().size());
        assertEquals("GITHUB", def.toolCapabilities().get(0));
        assertEquals(8, def.maxIterations());
        assertEquals("SYSTEM PROMPT INSTRUCTIONS", def.systemPrompt());
    }

    @Test
    void testMissingSecondDelimiterThrows() {
        String doc = """
                ---
                name: broken-skill
                description: Missing bottom dashes
                SYSTEM PROMPT INSTRUCTIONS
                """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parser.parse(doc));
        assertTrue(ex.getMessage().contains("exactly two ---"));
    }

    @Test
    void testMalformedYamlThrows() {
        String doc = """
                ---
                name: [ bad syntax
                ---
                prompt
                """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parser.parse(doc));
        assertTrue(ex.getMessage().contains("Failed to parse skill frontmatter"));
    }

    @Test
    void testAbsentDescriptionBecomesEmptyString() {
        String doc = """
                ---
                name: minimal
                ---
                prompt
                """;
        SkillDefinition def = parser.parse(doc);
        assertEquals("minimal", def.name());
        assertEquals("", def.description());
        assertEquals("prompt", def.systemPrompt());
    }

    @Test
    void testAbsentMaxIterationsDefaultsTo5() {
        String doc = """
                ---
                name: minimal
                ---
                prompt
                """;
        SkillDefinition def = parser.parse(doc);
        assertEquals(5, def.maxIterations());
    }
}
