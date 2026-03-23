package com.arcclaw.core.agent.persona;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BrahmaAgentTest {

    @Test
    void testAllowedToolsIsEmpty() {
        BrahmaAgent agent = new BrahmaAgent();
        assertTrue(agent.allowedTools().isEmpty(),
                "Coder role must have no tools — SANDBOX_EXEC deferred");
    }

    @Test
    void testSystemPromptDoesNotClaimGithubTool() {
        BrahmaAgent agent = new BrahmaAgent();
        assertFalse(agent.systemPrompt().contains("'github' tool"),
                "BrahmaAgent must not claim github tool it cannot access");
    }

    @Test
    void testSystemPromptMentionsNoTools() {
        BrahmaAgent agent = new BrahmaAgent();
        assertTrue(agent.systemPrompt().contains("AVAILABLE TOOLS: None"),
                "BrahmaAgent must declare no available tools");
    }
}
