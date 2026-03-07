package com.arcclaw.core.agent.reasoning;

import com.arcclaw.core.agent.AgentPersonaRegistry;
import com.arcclaw.core.agent.AgentRoleEnum;
import com.arcclaw.core.agent.PromptTemplateEngine;
import com.arcclaw.core.agent.RoleRegistry;
import com.arcclaw.core.state.CompactionSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SystemPromptBuilderTest {

    @Mock
    AgentPersonaRegistry agentPersonaRegistry;

    @Mock
    PromptTemplateEngine promptTemplateEngine;

    @Mock
    CompactionSummaryRepository compactionSummaryRepository;

    @Mock
    RoleRegistry roleRegistry;

    @InjectMocks
    SystemPromptBuilder builder;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testBuildSystemPrompt_sectionOrderIsPreserved() {
        // Arrange
        when(agentPersonaRegistry.getPersonaPrompt(AgentRoleEnum.COORDINATOR)).thenReturn("PERSONA_MARKER");
        when(compactionSummaryRepository.findLatestBySessionId(any())).thenReturn(Optional.empty());
        when(promptTemplateEngine.buildSystemPrompt(any(), any(), any())).thenReturn("TOOL_BINDING_MARKER");

        // Act
        String result = builder.build("coordinator", "s1");

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
        String resultCoord = builder.build("coordinator", "s1");
        assertTrue(resultCoord.contains("YOUR INTERNAL ARCHITECTURE"), "Coordinator should have trimurti block");

        String resultDebugger = builder.build("debugger", "s2");
        assertTrue(!resultDebugger.contains("YOUR INTERNAL ARCHITECTURE"), "Debugger should NOT have trimurti block");
    }
}
