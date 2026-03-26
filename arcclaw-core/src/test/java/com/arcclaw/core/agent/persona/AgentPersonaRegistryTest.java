package com.arcclaw.core.agent.persona;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentPersonaRegistryTest {

    private AgentPersonaRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentPersonaRegistry(List.of(
            new BrahmaAgent(),
            new ShivaAgent(),
            new VishnuAgent(),
            new SaraswatiAgent(),
            new BrihaspatiAgent(),
            new DattatreyaAgent()
        ));
    }

    // ── fixed test (was: testCoderPersonaContainsNoneTools) ──────────────────

    @Test
    void testCoderPersonaContainsSandboxExecTool() {
        String prompt = registry.getPersonaPrompt(AgentRoleEnum.CODER);
        assertTrue(
            prompt.contains("sandbox_exec") || prompt.contains("sandboxexec"),
            "Coder prompt should reference sandbox_exec tool — SANDBOX_EXEC is active since P67"
        );
        assertFalse(
            prompt.startsWith("PERMITTED TOOLS: None"),
            "Coder prompt must not declare zero tools"
        );
    }

    // ── restored passing tests — DO NOT delete these ──────────────────────────

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

    @Test
    void testUnknownRoleFallsBackToCoordinator() {
        AgentRoleEnum unknown = AgentRoleEnum.fromString("unknown-role");
        String prompt = registry.getPersonaPrompt(unknown);
        String coordinatorPrompt = registry.getPersonaPrompt(AgentRoleEnum.COORDINATOR);
        // Must return coordinator prompt specifically — not just any non-null string
        assertEquals(coordinatorPrompt, prompt,
            "Unknown role should fall back to coordinator persona");
    }

    @Test
    void testNullAgentsReturnsDefault() {
        AgentPersonaRegistry emptyRegistry = new AgentPersonaRegistry(List.of());
        String prompt = emptyRegistry.getPersonaPrompt(AgentRoleEnum.CODER);
        assertEquals("You are ArcClaw, an AI agent. Be helpful and concise.", prompt);
    }
}
