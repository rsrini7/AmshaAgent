package com.amsha.skills;

import static org.junit.jupiter.api.Assertions.*;

import com.amsha.core.agent.reasoning.PromptInstructionLibrary;
import com.amsha.core.skill.SkillDefinition;

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
        assertTrue(def.systemPrompt().startsWith(PromptInstructionLibrary.skillExecutionPrecedencePreamble()));
        assertTrue(def.systemPrompt().contains("SYSTEM PROMPT INSTRUCTIONS"));
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
        assertTrue(def.systemPrompt().startsWith(PromptInstructionLibrary.skillExecutionPrecedencePreamble()));
        assertTrue(def.systemPrompt().contains("prompt"));
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

    @Test
    void testAllowedToolsMapping() {
        String doc = """
                ---
                name: skillsjars-skill
                description: Uses allowed-tools
                allowed-tools: bash browser_navigate github web_search garuda nonexistent
                max-iterations: 8
                ---
                prompt
                """;

        SkillDefinition def = parser.parse(doc);
        assertEquals("skillsjars-skill", def.name());
        assertEquals(5, def.toolCapabilities().size());
        assertTrue(def.toolCapabilities().contains("SANDBOX_EXEC"));
        assertTrue(def.toolCapabilities().contains("BROWSER_NAVIGATE"));
        assertTrue(def.toolCapabilities().contains("GITHUB"));
        assertTrue(def.toolCapabilities().contains("WEB_SEARCH_BRAVE"));
        assertTrue(def.toolCapabilities().contains("DELEGATE_TO_AGENT"));
    }

    @Test
    void testHostExecAllowedToolsMapping() {
        String doc = """
                ---
                name: jdb-debugger
                description: Host debugger skill
                allowed-tools: HostExec(jdb:*) hostexec host bash
                max-iterations: 10
                ---
                prompt
                """;

        SkillDefinition def = parser.parse(doc);
        assertTrue(def.toolCapabilities().contains("HOST_EXEC"));
        assertTrue(def.toolCapabilities().contains("SANDBOX_EXEC"));
    }
}
