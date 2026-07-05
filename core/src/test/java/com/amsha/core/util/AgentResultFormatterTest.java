package com.amsha.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.amsha.core.agent.result.AgentResult;

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
}
