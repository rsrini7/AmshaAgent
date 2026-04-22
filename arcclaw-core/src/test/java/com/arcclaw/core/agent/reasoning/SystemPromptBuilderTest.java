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
        return new AgentExecutionContext(sessionId, "user-1", "chat-1", "coordinator", "goal", AutonomyLevel.FULL, 5, 0, null, null, null, null);
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

    @Test
    void testBuildSystemPrompt_compactModeRetainsCriticalStorageAndBrowserRules() {
        when(agentPersonaRegistry.getPersonaPrompt(any(), any())).thenReturn("PERSONA_MARKER");
        when(summaryCache.getSummary(any())).thenReturn(Optional.empty());
        when(promptTemplateEngine.buildSystemPrompt(any(), any(), any(), any())).thenReturn("TOOL_BINDING_MARKER");
        when(toolRegistry.findTool(any())).thenReturn(Optional.empty());
        builder.contextWindow = 8192;

        String result = builder.build(createCtx("s1"), "researcher", "s1", "some goal");

        assertTrue(result.contains("label is text before the first `=`"),
                "Compact prompt should preserve KEY=VALUE parsing guidance");
        assertTrue(result.contains("preserve them exactly"),
                "Compact prompt should preserve values containing additional '='");
        assertTrue(result.contains("retry once with a broader selector"),
                "Compact prompt should retain browser extraction retry guidance");
        assertTrue(result.contains("PERMITTED TOOLS: None"),
                "Compact prompt should use the minimal tool binding contract");
        assertTrue(!result.contains("TOOL_BINDING_MARKER"),
                "Compact prompt should not render the full role template");
        assertTrue(result.contains("If the user asks about your capabilities"),
                "Compact prompt should include cross-tool priority rules for tiny models");
        assertTrue(result.contains("A denied or failed tool call does not block a different valid tool"),
                "Compact prompt should avoid cross-turn suppression after a denied tool");
        assertTrue(result.contains("For capability/self-description questions, answer directly without memory_search"),
                "Compact binding should explicitly protect capability questions from memory_search");
        assertTrue(result.contains("For explicit owner/repo GitHub requests, prefer `github` over text-only fallback"),
                "Compact binding should explicitly prefer github for direct repo requests");
        assertTrue(!result.contains("YOUR INTERNAL ARCHITECTURE"),
                "Non-coordinator roles should not contain the coordinator architecture block");
    }

    @Test
    void testBuildSystemPrompt_compactModeWithoutBrowserOmitsRetryRule() {
        when(agentPersonaRegistry.getPersonaPrompt(any(), any())).thenReturn("PERSONA_MARKER");
        when(summaryCache.getSummary(any())).thenReturn(Optional.empty());
        when(promptTemplateEngine.buildSystemPrompt(any(), any(), any(), any())).thenReturn("TOOL_BINDING_MARKER");
        when(toolRegistry.findTool(any())).thenReturn(Optional.empty());
        builder.contextWindow = 8192;

        String result = builder.build(createCtx("s1"), "coordinator", "s1", "some goal");

        assertTrue(!result.contains("retry once with a broader selector"),
                "Compact browser retry rule should only appear for browser-capable roles");
    }
}
