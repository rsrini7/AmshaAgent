package com.amsha.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.message.UserMessages;

import org.junit.jupiter.api.Test;

class AgentResultFormatterTest {

    @Test
    void testFormatFailed_withNormalReason_prefixesWithX() {
        AgentResult failed =
                AgentResult.failed("Something went wrong", AgentResult.FailureCategory.SYSTEM_ERROR, List.of());
        String formatted = AgentResultFormatter.format(failed);
        assertEquals("❌ Something went wrong", formatted);
    }

    @Test
    void testFormatFailed_withRateLimitCategory_doesNotPrefixWithX() {
        // Sanitize should add ⏳
        AgentResult failed = AgentResult.failed("rate limit", AgentResult.FailureCategory.RATE_LIMIT, List.of());
        String formatted = AgentResultFormatter.format(failed);
        // We check that it starts with ⏳ and DOES NOT start with ❌ ⏳
        assertTrue(formatted.startsWith("⏳"));
        assertTrue(!formatted.startsWith("❌"));
    }

    @Test
    void testFormatFailed_withWaitEmojiPrefix_doesNotPrefixWithX() {
        AgentResult failed = AgentResult.failed("⏳ Waiting", AgentResult.FailureCategory.SYSTEM_ERROR, List.of());
        String formatted = AgentResultFormatter.format(failed);
        assertEquals("⏳ Waiting", formatted);
    }

    @Test
    void testFormatFailed_withProviderUnavailableMessage_isActionable() {
        AgentResult failed = AgentResult.failed(
                AgentErrorMessageUtil.SYSTEM_ERROR_PREFIX + UserMessages.AI_PROVIDER_UNAVAILABLE,
                AgentResult.FailureCategory.SYSTEM_ERROR,
                List.of());

        assertEquals("❌ " + UserMessages.AI_PROVIDER_UNAVAILABLE, AgentResultFormatter.format(failed));
    }

    @Test
    void testFormatFinished_stripsDsmlLeakTail() {
        AgentResult finished = AgentResult.finished(
                "I will now proceed.\n<｜｜DSML｜｜tool_calls>\n<｜｜DSML｜｜invoke name=\"host_exec\">\n", List.of());
        String formatted = AgentResultFormatter.format(finished);
        assertEquals("I will now proceed.", formatted);
    }

    @Test
    void testFormatFinished_stripsXmlToolCallTail() {
        AgentResult finished = AgentResult.finished("""
                I will now proceed.
                <tool_call>
                  <function=host_exec>
                    <parameter=code>ls -la /tmp</parameter>
                  </function>
                </tool_call>
                """, List.of());
        String formatted = AgentResultFormatter.format(finished);
        assertEquals("I will now proceed.", formatted);
    }

    @Test
    void testFormatFinished_collapsesHtmlArtifact() {
        AgentResult finished = AgentResult.finished("""
                <!doctype html>
                <html>
                  <head><title>Simple Particle System</title></head>
                  <body>
                    <canvas id="scene"></canvas>
                    <script>console.log('render');</script>
                  </body>
                </html>
                """, List.of());
        String formatted = AgentResultFormatter.format(finished);
        assertEquals("[HTML artifact generated - open the browser preview to view it.]", formatted);
    }

    @Test
    void testFormatFinished_collapsesEmbeddedHtmlArtifact() {
        AgentResult finished = AgentResult.finished("""
                The approved tool was executed successfully and the requested artifact was generated.

                <!doctype html>
                <html>
                  <head><title>Simple Particle System</title></head>
                  <body>
                    <canvas id="scene"></canvas>
                    <script>console.log('render');</script>
                  </body>
                </html>
                """, List.of());
        String formatted = AgentResultFormatter.format(finished);
        assertEquals("[HTML artifact generated - open the browser preview to view it.]", formatted);
    }

    @Test
    void testFormatAwaitingApproval_pipeDelimitedBatch() {
        AgentResult awaiting =
                AgentResult.awaitingApproval("parallel|web_fetch|serper_web_search", "{}", "sess-1", List.of());
        String formatted = AgentResultFormatter.format(awaiting);
        assertTrue(formatted.contains("• web_fetch"));
        assertTrue(formatted.contains("• serper_web_search"));
        assertTrue(!formatted.contains("• web_fetch|serper_web_search"));
    }

    @Test
    void testFormatAwaitingApproval_commaDelimitedBatch() {
        AgentResult awaiting =
                AgentResult.awaitingApproval("parallel|web_fetch,serper_web_search", "{}", "sess-1", List.of());
        String formatted = AgentResultFormatter.format(awaiting);
        assertTrue(formatted.contains("• web_fetch"));
        assertTrue(formatted.contains("• serper_web_search"));
        assertTrue(!formatted.contains("• web_fetch,serper_web_search"));
    }

    @Test
    void testFormatHitlPrompt() {
        String prompt = AgentResultFormatter.formatHitlPrompt("parallel|browser_navigate", null, "sess-1", false);
        assertTrue(prompt.contains("• browser_navigate"));
        assertTrue(prompt.contains("/approve <toolName>"));
    }
}
