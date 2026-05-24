package com.arcclaw.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.arcclaw.core.agent.reasoning.prompt.PromptRegistry;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolRegistry;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PromptTemplateEngineTest {

    private PromptTemplateEngine engine;
    private Engine quteEngine;

    @BeforeEach
    void setup() {
        engine = new PromptTemplateEngine();
        engine.toolRegistry = mock(ToolRegistry.class);
        when(engine.toolRegistry.findTool(anyString())).thenReturn(Optional.of(mock(AgentTool.class)));
        engine.promptRegistry = new PromptRegistry() {
            @Override public String get(String roleId, String sectionKey) { return null; }
            @Override public String get(String roleId, String sectionKey, String fallback) { return fallback; }
        };
        engine.engine = Engine.builder().addDefaults().build();
        // Standalone Qute engine with defaults (which includes Map and Reflection resolvers)
        quteEngine = Engine.builder()
            .addDefaults()
            .build();

        // Mock templates
        engine.coordinatorTemplate = quteEngine.parse("Coordinator: {compactionSummary ?: 'No summary'} {systemTimeUtc ?: 'No time'}");
        engine.researcherTemplate = quteEngine.parse("Researcher Template");
        engine.coderTemplate = quteEngine.parse("Coder");
        engine.debuggerTemplate = quteEngine.parse("Debugger");
        engine.architectTemplate = quteEngine.parse("Architect");
        engine.synthesizerTemplate = quteEngine.parse("Synthesizer");
    }

    @Test
    void testBuildSystemPrompt_withNulls() {
        String result = engine.buildSystemPrompt(AgentRoleEnum.COORDINATOR, null, null);
        assertNotNull(result);
        assertTrue(result.contains("No summary") || result.contains("Coordinator:"), "Actual: " + result);
    }

    @Test
    void testBuildSystemPrompt_withData() {
        Instant now = Instant.parse("2026-04-01T10:00:00Z");
        String result = engine.buildSystemPrompt(AgentRoleEnum.COORDINATOR, "Summary info", now);
        assertTrue(result.contains("Summary info"), "Actual: " + result);
        assertTrue(result.contains("2026-04-01T10:00Z"), "Actual: " + result);
    }

    @Test
    void testBuildSystemPrompt_researcher() {
        String result = engine.buildSystemPrompt(AgentRoleEnum.RESEARCHER, null, null);
        assertTrue(result.contains("Researcher"), "Actual: " + result);
    }
}
