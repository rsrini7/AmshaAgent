package com.arcclaw.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.arcclaw.core.agent.persona.AgentPersonaRegistry;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.skill.SkillRegistry;
import com.arcclaw.core.state.CompactionSummaryRepository;

class SystemPromptBuilderTest {

    @Mock
    AgentPersonaRegistry agentPersonaRegistry;

    @Mock
    PromptTemplateEngine promptTemplateEngine;

    @Mock
    CompactionSummaryRepository compactionSummaryRepository;

    @Mock
    SkillRegistry skillRegistry;

    SystemPromptBuilder builder;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        builder = new SystemPromptBuilder(agentPersonaRegistry, promptTemplateEngine,
                compactionSummaryRepository, skillRegistry);
    }

    @Test
    void testBuildSystemPrompt_sectionOrderIsPreserved() {
        // Arrange
        when(agentPersonaRegistry.getPersonaPrompt(AgentRoleEnum.COORDINATOR)).thenReturn("PERSONA_MARKER");
        when(compactionSummaryRepository.findLatestBySessionId(any())).thenReturn(Optional.empty());
        when(promptTemplateEngine.buildSystemPrompt(any(), any(), any())).thenReturn("TOOL_BINDING_MARKER");

        // Act
        String result = builder.build("coordinator", "s1", "some goal");

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
        when(agentPersonaRegistry.getPersonaPrompt(any())).thenReturn("MOCK_PERSONA");

        // Act & Assert
        String resultCoord = builder.build("coordinator", "s1", null);
        assertTrue(resultCoord.contains("YOUR INTERNAL ARCHITECTURE"), "Coordinator should have trimurti block");

        String resultDebugger = builder.build("debugger", "s2", null);
        assertTrue(!resultDebugger.contains("YOUR INTERNAL ARCHITECTURE"), "Debugger should NOT have trimurti block");
    }
}
