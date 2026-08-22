package com.amsha.tool.playwright;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Map;

import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.Test;

class PlaywrightCliToolCoverageTest {

    @Test
    void testMetadataAndConfiguration() {
        PlaywrightCliTool tool = new PlaywrightCliTool();
        assertEquals(ToolCapability.BROWSER_NAVIGATE.toolName(), tool.name());
        assertTrue(tool.description().contains("Full browser automation"));
        assertFalse(tool.requiresSandbox());
        assertEquals(ToolOperation.NETWORK_OUTBOUND, tool.requiredOperation());
        assertEquals(IdempotencyMode.IDEMPOTENT, tool.idempotencyMode());
        assertNotNull(tool.parametersSchema());
        assertFalse(tool.requiresManualApproval(Map.of()));

        tool.configEnabled = true;
        assertTrue(tool.isEnabled());
        tool.configEnabled = false;
        assertFalse(tool.isEnabled());

        // health check disabled
        ToolResponse hc = tool.healthCheck();
        assertNotNull(hc);
        assertFalse(hc.success());
    }

    @Test
    void testStaticHelperMethods() throws Exception {
        // sessionName
        Method sessionNameMethod = PlaywrightCliTool.class.getDeclaredMethod("sessionName", String.class);
        sessionNameMethod.setAccessible(true);
        assertEquals("amsha-default", sessionNameMethod.invoke(null, (String) null));
        String s1 = (String) sessionNameMethod.invoke(null, "session-12345");
        assertTrue(s1.startsWith("amsha-"));

        // escapeJsString
        Method escapeMethod = PlaywrightCliTool.class.getDeclaredMethod("escapeJsString", String.class);
        escapeMethod.setAccessible(true);
        assertEquals("hello\\\\world", escapeMethod.invoke(null, "hello\\world"));
        assertEquals("it\\'s", escapeMethod.invoke(null, "it's"));
        assertEquals("line1\\nline2", escapeMethod.invoke(null, "line1\nline2"));

        // truncateAtSentence
        assertEquals("Short.", PlaywrightCliTool.truncateAtSentence("Short.", 10));
        assertEquals("First sentence.", PlaywrightCliTool.truncateAtSentence("First sentence. Second sentence.", 20));
        assertEquals("No sentence ending here", PlaywrightCliTool.truncateAtSentence("No sentence ending here", 23));
    }

    @Test
    void testExecuteInputValidationErrors() {
        PlaywrightCliTool tool = new PlaywrightCliTool();
        tool.configEnabled = true;

        ToolExecutionContext ctx = new ToolExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coder",
                AutonomyLevel.FULL,
                "trace-1",
                "telegram",
                "plat-1",
                null,
                null,
                null,
                null);

        // Disabled
        tool.configEnabled = false;
        ToolResponse r0 = tool.execute(ctx, Map.of());
        assertFalse(r0.success());
        assertEquals(503, r0.exitCode());

        tool.configEnabled = true;

        // Missing userId
        ToolExecutionContext noUserCtx = new ToolExecutionContext(
                "sess-1",
                null,
                "chat-1",
                "coder",
                AutonomyLevel.FULL,
                "trace-1",
                "telegram",
                "plat-1",
                null,
                null,
                null,
                null);
        ToolResponse rNoUser = tool.execute(noUserCtx, Map.of());
        assertFalse(rNoUser.success());
        assertEquals(403, rNoUser.exitCode());

        // Missing url for extract_text
        ToolResponse r1 = tool.execute(ctx, Map.of());
        assertFalse(r1.success());
        assertTrue(r1.output().contains("Parameter 'url' is required"));

        // Unsupported URL scheme
        ToolResponse r2 = tool.execute(ctx, Map.of("action", "navigate", "url", "file:///secret"));
        assertFalse(r2.success());
        assertTrue(r2.output().contains("Unsupported URL scheme"));

        // Missing selector for click
        ToolResponse r3 = tool.execute(ctx, Map.of("action", "click"));
        assertFalse(r3.success());
        assertTrue(r3.output().contains("Parameter 'selector' is required"));

        // Missing value for fill
        ToolResponse r4 = tool.execute(ctx, Map.of("action", "fill", "selector", "#input"));
        assertFalse(r4.success());
        assertTrue(r4.output().contains("Parameter 'value' is required"));

        // Missing script for eval
        ToolResponse r5 = tool.execute(ctx, Map.of("action", "eval"));
        assertFalse(r5.success());
        assertTrue(r5.output().contains("Parameter 'script' is required"));
    }
}
