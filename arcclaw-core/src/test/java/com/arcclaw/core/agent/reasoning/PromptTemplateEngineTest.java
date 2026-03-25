package com.arcclaw.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PromptTemplateEngineTest {

    @Inject
    PromptTemplateEngine promptTemplateEngine;

    @Test
    void testCoordinatorPromptContainsGithubToolName() {
        String prompt = promptTemplateEngine.buildSystemPrompt(AgentRoleEnum.COORDINATOR, null, Instant.now());
        assertTrue(prompt.contains("github"), "Coordinator prompt should contain 'github' tool");
        assertTrue(prompt.contains("schedule_task"), "Coordinator prompt should contain 'schedule_task' tool");
        assertTrue(prompt.contains("manage_schedule"), "Coordinator prompt should contain 'manage_schedule' tool");
    }

    @Test
    void testResearcherPromptContainsOwnerRequired() {
        String prompt = promptTemplateEngine.buildSystemPrompt(AgentRoleEnum.RESEARCHER, null, Instant.now());
        assertTrue(prompt.contains("OWNER REQUIRED"), "Researcher prompt should contain 'OWNER REQUIRED' instruction");
        assertTrue(prompt.contains("github"), "Researcher prompt should contain 'github' tool");
    }

    @Test
    void testCoderPromptContainsNoneToolsSection() {
        String prompt = promptTemplateEngine.buildSystemPrompt(AgentRoleEnum.CODER, null, Instant.now());
        assertTrue(prompt.contains("PERMITTED TOOLS: None"), "Coder prompt should explicitly state no tools");
    }

    @Test
    void testCompactionSummaryAppearsWhenProvided() {
        String summary = "This is a past summary";
        String prompt = promptTemplateEngine.buildSystemPrompt(AgentRoleEnum.COORDINATOR, summary, Instant.now());
        assertTrue(prompt.contains("[CONTEXT FROM PRIOR SESSION - FOR REFERENCE ONLY]"), "Prompt should contain compaction header");
        assertTrue(prompt.contains(summary), "Prompt should contain the actual summary");
    }

    @Test
    void testCompactionSummaryAbsentWhenNull() {
        String prompt = promptTemplateEngine.buildSystemPrompt(AgentRoleEnum.COORDINATOR, null, Instant.now());
        assertFalse(prompt.contains("[CONTEXT FROM PRIOR SESSION - FOR REFERENCE ONLY]"), "Prompt should not contain compaction header when null");
    }

    @Test
    void testAllRolesRenderWithoutException() {
        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            // Test with both current time and null
            assertNotNull(promptTemplateEngine.buildSystemPrompt(role, "summary", Instant.now()));
            assertNotNull(promptTemplateEngine.buildSystemPrompt(role, null, null));
        }
    }
}
