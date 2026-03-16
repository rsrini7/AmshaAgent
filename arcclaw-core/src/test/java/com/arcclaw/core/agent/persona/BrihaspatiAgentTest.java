package com.arcclaw.core.agent.persona;

import com.arcclaw.core.agent.role.AgentRoleEnum;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class BrihaspatiAgentTest {

    @Test
    void testRoleCorrectness() {
        BrihaspatiAgent agent = new BrihaspatiAgent();
        assertEquals("architect", agent.role());
    }

    @Test
    void testSystemPromptDefined() {
        BrihaspatiAgent agent = new BrihaspatiAgent();
        assertNotNull(agent.systemPrompt());
        assertFalse(agent.systemPrompt().isBlank());
        assertTrue(agent.systemPrompt().contains("write production code"));
    }

    @Test
    void testAllowedToolsDelegatesToEnumDefinition() {
        BrihaspatiAgent agent = new BrihaspatiAgent();
        List<String> tools = agent.allowedTools();
        
        Set<String> expectedTools = AgentRoleEnum.ARCHITECT.definition().toolNames();
        assertEquals(expectedTools.size(), tools.size());
        assertTrue(tools.containsAll(expectedTools));
    }
}
