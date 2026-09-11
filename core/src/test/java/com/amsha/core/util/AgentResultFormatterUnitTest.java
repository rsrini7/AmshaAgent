package com.amsha.core.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.message.UserMessages;

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

    @Test
    void testFormatCronHitlWithParams() {
        String hitl = AgentResultFormatter.formatCronHitl(
                "cron-123", "Daily Backup", "execute_shell", "{\"command\":\"backup.sh\"}");
        assertTrue(hitl.contains("Approval Required for background task"));
        assertTrue(hitl.contains("Daily Backup"));
        assertTrue(hitl.contains("backup.sh"));
        assertTrue(hitl.contains("Reply /approve cron-123 or /deny cron-123"));
    }

    @Test
    void testSanitizeCriticalDirectiveTailFromFinishedAnswer() {
        String answer =
                "Delegation is waiting for approval.\n\n" + "CRITICAL DIRECTIVE: Do NOT retry delegation. FINISH now.";

        String formatted = AgentResultFormatter.format(AgentResult.finished(answer, List.of()));

        assertEquals("Delegation is waiting for approval.", formatted);
        assertFalse(formatted.contains("CRITICAL DIRECTIVE"));
        assertFalse(formatted.contains("FINISH now"));
    }

    @Test
    void testSanitizeCriticalDirectiveTailFromJsonWrappedFinishedAnswer() {
        String answer = "{\"response\":\"Safe answer CRITICAL DIRECTIVE: FINISH now.\"}";

        String formatted = AgentResultFormatter.format(AgentResult.finished(answer, List.of()));

        assertEquals("Safe answer", formatted);
        assertFalse(formatted.contains("CRITICAL DIRECTIVE"));
    }

    @Test
    void testMalformedToolArrayUsesSafeUserFacingFallback() {
        String answer = "[{\"name\":\"garuda\",\"arguments\":{\"agent\":\"brahma\"}}]";

        String formatted = AgentResultFormatter.format(AgentResult.finished(answer, List.of()));

        assertEquals(UserMessages.AI_UNEXPECTED_RESPONSE, formatted);
        assertFalse(formatted.contains("Tool output was malformed"));
    }
}
