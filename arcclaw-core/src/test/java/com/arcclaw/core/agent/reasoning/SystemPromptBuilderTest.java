package com.arcclaw.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Optional;

import com.arcclaw.core.agent.loop.AgentExecutionContext;
import com.arcclaw.core.agent.persona.AgentPersonaRegistry;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.skill.SkillRegistry;
import com.arcclaw.core.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class SystemPromptBuilderTest {

    @Mock
    AgentPersonaRegistry agentPersonaRegistry;

    @Mock
    PromptTemplateEngine promptTemplateEngine;

    @Mock
    CompactionSummaryCache summaryCache;

    @Mock
    SkillRegistry skillRegistry;

    @Mock
    ToolRegistry toolRegistry;

    SystemPromptBuilder builder;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        builder = new SystemPromptBuilder(agentPersonaRegistry, promptTemplateEngine,
                summaryCache, skillRegistry, toolRegistry);
    }

    private AgentExecutionContext createCtx(String sessionId) {
        return new AgentExecutionContext(sessionId, "user-1", "chat-1", "coordinator", "goal", AutonomyLevel.FULL, 5, null, null, null, null);
    }

    @Test
    void testBuildSystemPrompt_sectionOrderIsPreserved() {
        // Arrange
        when(agentPersonaRegistry.getPersonaPrompt(any(), any())).thenReturn("PERSONA_MARKER");
        when(summaryCache.getSummary(any())).thenReturn(Optional.empty());
        when(promptTemplateEngine.buildSystemPrompt(any(), any(), any(), any())).thenReturn("TOOL_BINDING_MARKER");
        when(toolRegistry.listTools()).thenReturn(Collections.emptyList());

        // Act
        String result = builder.build(createCtx("s1"), "coordinator", "s1", "some goal");

        // Assert
        int arcClawIdx = result.indexOf("You are ArcClaw");
        int personaIdx = result.indexOf("PERSONA_MARKER");
        int secRulesIdx = result.indexOf("RESPONSE LENGTH RULE");
        int bindingIdx = result.indexOf("STRICT TOOL BINDING CONTRACT");

        assertTrue(arcClawIdx < personaIdx, "ArcClaw prefix should come first");
        assertTrue(personaIdx < secRulesIdx, "Persona should come before security rules");
        assertTrue(secRulesIdx < bindingIdx, "Security rules should come before tool binding");
    }

    @Test
    void testBuildSystemPrompt_coordinatorBlockOnlyForCoordinator() {
        // Arrange
        when(agentPersonaRegistry.getPersonaPrompt(any(), any())).thenReturn("MOCK_PERSONA");

        // Act & Assert
        String resultCoord = builder.build(createCtx("s1"), "coordinator", "s1", null);
        assertTrue(resultCoord.contains("YOUR INTERNAL ARCHITECTURE"), "Coordinator should have trimurti block");

        String resultDebugger = builder.build(createCtx("s2"), "debugger", "s2", null);
        assertTrue(!resultDebugger.contains("YOUR INTERNAL ARCHITECTURE"), "Debugger should NOT have trimurti block");
    }
}
