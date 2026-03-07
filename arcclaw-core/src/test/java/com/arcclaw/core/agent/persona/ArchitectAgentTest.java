package com.arcclaw.core.agent.persona;

import com.arcclaw.core.agent.role.AgentRoleEnum;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ArchitectAgentTest {

    @Test
    void testRoleCorrectness() {
        ArchitectAgent agent = new ArchitectAgent();
        assertEquals("architect", agent.role());
    }

    @Test
    void testSystemPromptDefined() {
        ArchitectAgent agent = new ArchitectAgent();
        assertNotNull(agent.systemPrompt());
        assertFalse(agent.systemPrompt().isBlank());
        assertTrue(agent.systemPrompt().contains("write production code"));
    }

    @Test
    void testAllowedToolsDelegatesToEnumDefinition() {
        ArchitectAgent agent = new ArchitectAgent();
        List<String> tools = agent.allowedTools();
        
        Set<String> expectedTools = AgentRoleEnum.ARCHITECT.definition().toolNames();
        assertEquals(expectedTools.size(), tools.size());
        assertTrue(tools.containsAll(expectedTools));
    }
}
