package com.arcclaw.core.agent.persona;

import com.arcclaw.core.agent.role.AgentRoleEnum;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class DattatreyaAgentTest {

    @Test
    void testRoleIsSynthesizer() {
        assertEquals("synthesizer", new DattatreyaAgent().role());
    }

    @Test
    void testSystemPromptContainsDattatreya() {
        String prompt = new DattatreyaAgent().systemPrompt();
        assertTrue(prompt.contains("Dattatreya"));
    }

    @Test
    void testSystemPromptMentionsAllSpecialists() {
        String prompt = new DattatreyaAgent().systemPrompt();
        assertTrue(prompt.contains("Brahma"), "Prompt should mention Brahma");
        assertTrue(prompt.contains("Shiva"), "Prompt should mention Shiva");
        assertTrue(prompt.contains("Saraswati"), "Prompt should mention Saraswati");
        assertTrue(prompt.contains("Brihaspati"), "Prompt should mention Brihaspati");
    }

    @Test
    void testAllowedToolsMatchEnum() {
        assertEquals(
            AgentRoleEnum.SYNTHESIZER.definition().toolNames(),
            new HashSet<>(new DattatreyaAgent().allowedTools()));
    }

    @Test
    void testSystemPromptForbidsCodeGeneration() {
        String prompt = new DattatreyaAgent().systemPrompt();
        assertTrue(prompt.contains("MUST NOT generate code from scratch"));
    }

    @Test
    void testSystemPromptForbidsPureResearch() {
        String prompt = new DattatreyaAgent().systemPrompt();
        assertTrue(prompt.contains("MUST NOT perform pure research"));
    }
}
