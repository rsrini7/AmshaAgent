package com.amsha.core.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.amsha.core.agent.result.AgentResult;

import org.junit.jupiter.api.Test;

class AgentResultFormatterUnitTest {

    @Test
    void testFormatFinishedResult() {
        AgentResult result = AgentResult.finished("The answer is 42", List.of());
        String formatted = AgentResultFormatter.format(result);
        assertEquals("The answer is 42", formatted);
    }

    @Test
    void testFormatAwaitingApproval() {
        AgentResult result =
                AgentResult.awaitingApproval("execute_shell", "{\"command\":\"rm -rf /\"}", "sess-1", List.of());
        String formatted = AgentResultFormatter.format(result);
        assertTrue(formatted.contains("Approval needed for tool: execute_shell"));
        assertTrue(formatted.contains("rm -rf /"));
    }

    @Test
    void testFormatLoopExceeded() {
        AgentResult result = AgentResult.loopExceeded(10, List.of());
        String formatted = AgentResultFormatter.format(result);
        assertTrue(formatted.contains("ran out of steps"));
    }

    @Test
    void testFormatFailed() {
        AgentResult result =
                AgentResult.failed("Database connection refused", AgentResult.FailureCategory.SYSTEM_ERROR, List.of());
        String formatted = AgentResultFormatter.format(result);
        assertTrue(formatted.contains("Database connection refused"));
    }

    @Test
    void testSanitizeToolcallMarkup() {
        String text = "Here is the output <TOOLCALL>{\"name\":\"test\"}</TOOLCALL> and more text";
        String sanitized = AgentResultFormatter.sanitize(text);
        assertEquals("Here is the output  and more text", sanitized);
    }

    @Test
    void testFormatCronHitl() {
        String hitl = AgentResultFormatter.formatCronHitl("cron-123", "Daily Backup", "execute_shell");
        assertTrue(hitl.contains("Approval Required for background task"));
        assertTrue(hitl.contains("Daily Backup"));
    }
}
