package com.arcclaw.core.agent.persona;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.arcclaw.core.agent.loop.AgentExecutionContext;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.security.AutonomyLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentPersonaRegistryTest {

    private AgentPersonaRegistry registry;

    @BeforeEach
    void setUp() {
        // Mock individual agents to verify registry lookup logic
        Agent coder = mock(Agent.class);
        when(coder.role()).thenReturn("coder");
        when(coder.systemPrompt(any())).thenReturn("Coder Prompt");

        Agent coordinator = mock(Agent.class);
        when(coordinator.role()).thenReturn("coordinator");
        when(coordinator.systemPrompt(any())).thenReturn("Coordinator Prompt");

        registry = new AgentPersonaRegistry(List.of(coder, coordinator));
    }

    private AgentExecutionContext createCtx() {
        return new AgentExecutionContext("s1", "u1", "c1", "role", "goal", AutonomyLevel.FULL, 5, null, null, null, null);
    }

    @Test
    void testRegistryLookup() {
        String prompt = registry.getPersonaPrompt(createCtx(), AgentRoleEnum.CODER);
        assertEquals("Coder Prompt", prompt);
    }

    @Test
    void testCoordinatorFallback() {
        String prompt = registry.getPersonaPrompt(createCtx(), AgentRoleEnum.RESEARCHER);
        assertEquals("Coordinator Prompt", prompt, 
            "Should fall back to coordinator prompt when research agent is missing");
    }

    @Test
    void testUltimateFallback() {
        AgentPersonaRegistry empty = new AgentPersonaRegistry(List.of());
        String prompt = empty.getPersonaPrompt(createCtx(), AgentRoleEnum.CODER);
        assertEquals("You are ArcClaw, an AI agent. Be helpful and concise.", prompt);
    }
}
