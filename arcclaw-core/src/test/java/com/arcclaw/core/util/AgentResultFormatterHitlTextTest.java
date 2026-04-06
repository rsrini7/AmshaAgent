package com.arcclaw.core.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.arcclaw.core.agent.result.AgentResult;
import org.junit.jupiter.api.Test;

class AgentResultFormatterHitlTextTest {

    @Test
    void formatHitlMessage_containsNoMarkdownV2Markers() {
        String toolName = "parallel|github,weather";
        String params = "{\"q\":\"London\"}";
        
        // AwaitingApproval(toolName, paramsJson, sessionId, steps)
        AgentResult awaiting = AgentResult.awaitingApproval(
            toolName, params, "sess-123", List.of());
        String output = AgentResultFormatter.format(awaiting);
        
        // No bold markers
        assertFalse(output.contains("**"), "Should not contain bold markers");
        
        // No backticks in command examples
        assertFalse(output.contains("`/approve"), "Should not contain backticks in command examples");
        
        // Plain text placeholders
        assertTrue(output.contains("<toolName>"), "Should contain plain placeholders");
        
        // Bullet points are plain
        assertTrue(output.contains("• github"), "Should contain plain bullet list");
        assertFalse(output.contains("• `github`"), "Should not contain backticks around tool names");
    }

    @Test
    void formatHitlMessage_singleTool_isPlainText() {
        AgentResult awaiting = AgentResult.awaitingApproval(
            "github", "{\"cmd\":\"ls\"}", "sess-456", List.of());
        String output = AgentResultFormatter.format(awaiting);
        
        assertFalse(output.contains("`/approve`"), "Single tool HITL should not have backticks");
        assertTrue(output.contains("Reply /approve"), "Single tool HITL should be plain text");
    }
}
