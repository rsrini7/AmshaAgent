package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import com.amsha.core.agent.reasoning.prompt.PromptRegistry;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.tool.api.AgentTool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.qute.Engine;

class PromptTemplateEngineTest {

    @Test
    void coordinatorSchedulePromptMatchesSupportedScheduleTaskParameters() throws Exception {
        try (var stream =
                PromptTemplateEngineTest.class.getResourceAsStream("/templates/prompts/role-coordinator.txt")) {
            assertNotNull(stream, "Coordinator prompt template should be packaged");
            String prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(prompt.contains("`timezone`"));
            assertTrue(prompt.contains("`executionRole`"));
            assertTrue(prompt.contains("`executionRole` to `coder`"));
            assertFalse(prompt.contains("EXACTLY THREE parameters"));
            assertFalse(prompt.contains("MANDATORY TIME MATH"));
            assertFalse(prompt.contains("Write ONLY the UTC result into cronExpression"));
            assertFalse(prompt.contains("mathematically add X to the current system minute"));
        }
        try (var stream = PromptTemplateEngineTest.class.getResourceAsStream("/templates/personas/coordinator.txt")) {
            assertNotNull(stream, "Coordinator persona template should be packaged");
            String persona = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(persona.contains("omit `cronExpression`"));
            assertTrue(persona.contains("pass its IANA name separately in `timezone`"));
            assertTrue(persona.contains("For a one-time relative request"));
            assertFalse(persona.contains("Use the `SUGGESTED_CRON` above for the `schedule_task` tool"));
            assertFalse(persona.contains("Derive the cron expression from that UTC timestamp"));
            assertFalse(persona.contains("NEVER include the timezone name in the cron expression itself"));
            assertFalse(persona.contains("ask the user to clarify"));
        }
    }

    private PromptTemplateEngine engine;
    private Engine quteEngine;

    @BeforeEach
    void setup() {
        engine = new PromptTemplateEngine();
        engine.toolRegistry = mock(ToolRegistry.class);
        when(engine.toolRegistry.findTool(anyString())).thenReturn(Optional.of(mock(AgentTool.class)));
        engine.roleRegistry = mock(RoleRegistry.class);
        when(engine.roleRegistry.resolveRoleDefinition(anyString())).thenAnswer(inv -> {
            String roleId = inv.getArgument(0);
            return AgentRoleEnum.fromString(roleId).definition();
        });
        engine.promptRegistry = new PromptRegistry() {
            @Override
            public String get(String roleId, String sectionKey) {
                return null;
            }

            @Override
            public String get(String roleId, String sectionKey, String fallback) {
                return fallback;
            }
        };
        engine.engine = Engine.builder().addDefaults().build();
        // Standalone Qute engine with defaults (which includes Map and Reflection resolvers)
        quteEngine = Engine.builder().addDefaults().build();

        // Mock templates
        engine.coordinatorTemplate =
                quteEngine.parse("Coordinator: {compactionSummary ?: 'No summary'} {systemTimeUtc ?: 'No time'}");
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
