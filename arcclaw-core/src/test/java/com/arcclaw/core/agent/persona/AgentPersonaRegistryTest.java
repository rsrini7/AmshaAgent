package com.arcclaw.core.agent.persona;

import com.arcclaw.core.agent.role.AgentRoleEnum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentPersonaRegistryTest {

    private AgentPersonaRegistry registry;

    @BeforeEach
    void setUp() {
        // Build synthetic Iterable<Agent> for pure JUnit5 setup
        List<Agent> agents = Arrays.asList(
                new BrihaspatiAgent(),
                new BrahmaAgent(),
                new DattatreyaAgent(),
                new SaraswatiAgent(),
                new ShivaAgent(),
                new VishnuAgent()
        );
        registry = new AgentPersonaRegistry(agents);
    }

    @Test
    void testSynthesizerPersonaContainsDattatreya() {
        String prompt = registry.getPersonaPrompt(AgentRoleEnum.SYNTHESIZER);
        assertTrue(prompt.contains("Dattatreya"), "Synthesizer prompt should contain Dattatreya");
    }

    @Test
    void testCoordinatorPersonaContainsVishnu() {
        String prompt = registry.getPersonaPrompt(AgentRoleEnum.COORDINATOR);
        assertTrue(prompt.contains("Vishnu"), "Coordinator prompt should contain Vishnu");
    }

    @Test
    void testDebuggerPersonaContainsShiva() {
        String prompt = registry.getPersonaPrompt(AgentRoleEnum.DEBUGGER);
        assertTrue(prompt.contains("Shiva"), "Debugger prompt should contain Shiva");
    }

    @Test
    void testCoderPersonaContainsNoneTools() {
        String prompt = registry.getPersonaPrompt(AgentRoleEnum.CODER);
        assertTrue(prompt.contains("AVAILABLE TOOLS: None"), "Coder prompt should declare zero tools");
    }

    @Test
    void testResearcherPersonaContainsSaraswati() {
        String prompt = registry.getPersonaPrompt(AgentRoleEnum.RESEARCHER);
        assertTrue(prompt.contains("Saraswati"), "Researcher prompt should contain Saraswati");
    }

    @Test
    void testAllRolesHavePersonaBean() {
        String fallback = "You are ArcClaw, an AI agent. Be helpful and concise.";
        
        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            String prompt = registry.getPersonaPrompt(role);
            assertNotNull(prompt, "Prompt should not be null for " + role);
            assertFalse(prompt.isBlank(), "Prompt should not be blank for " + role);
            assertNotEquals(fallback, prompt, "Should not hit ultimate fallback for " + role);
        }
    }
}
