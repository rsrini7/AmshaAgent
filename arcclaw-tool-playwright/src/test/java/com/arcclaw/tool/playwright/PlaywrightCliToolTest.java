package com.arcclaw.tool.playwright;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
import org.junit.jupiter.api.Test;

class PlaywrightCliToolTest {

    private final PlaywrightCliTool tool = new PlaywrightCliTool();

    @Test
    void name() {
        assertEquals(ToolCapability.BROWSER_NAVIGATE.toolName(), tool.name());
    }

    @Test
    void requiresSandbox() {
        assertFalse(tool.requiresSandbox());
    }

    @Test
    void disabledByDefault() {
        assertFalse(tool.isEnabled());
    }

    @Test
    void healthCheckReturns503WhenDisabled() {
        ToolResponse r = tool.healthCheck();
        assertFalse(r.success());
        assertEquals(503, r.exitCode());
        assertTrue(r.output().contains("disabled"));
    }

    private static ToolExecutionContext ctx(String sid, String uid) {
        return new ToolExecutionContext(sid, uid, null, "researcher",
            null, null, null, null, null, null, null, null);
    }

    @Test
    void executeReturns503WhenDisabled() {
        ToolResponse r = tool.execute(ctx("s1", "u1"), Map.of("url", "https://example.com"));
        assertFalse(r.success());
        assertEquals(503, r.exitCode());
    }

    @Test
    void executeRequiresUserId() {
        tool.configEnabled = true;
        ToolResponse r = tool.execute(ctx("s1", null), Map.of("url", "https://example.com"));
        assertFalse(r.success());
        assertEquals(403, r.exitCode());
    }

    @Test
    void executeRequiresUrl() {
        tool.configEnabled = true;
        ToolResponse r = tool.execute(ctx("s1", "u1"), Map.of());
        assertFalse(r.success());
        assertEquals(400, r.exitCode());
    }

    @Test
    void rejectNonHttpUrl() {
        tool.configEnabled = true;
        ToolResponse r = tool.execute(ctx("s1", "u1"), Map.of("url", "file:///etc/passwd"));
        assertFalse(r.success());
        assertEquals(400, r.exitCode());
        assertTrue(r.output().contains("Unsupported URL scheme"));
    }

    @Test
    void rejectBlankUrl() {
        tool.configEnabled = true;
        ToolResponse r = tool.execute(ctx("s1", "u1"), Map.of("url", "  "));
        assertFalse(r.success());
        assertEquals(400, r.exitCode());
    }

    @Test
    void truncateAtSentenceBoundary() {
        String longText = "A. B. C. " + "x".repeat(9100) + " D. E.";
        String truncated = PlaywrightCliTool.truncateAtSentence(longText, 100);
        assertTrue(truncated.length() <= 100, "truncated length=" + truncated.length());
    }

    @Test
    void shortTextNotTruncated() {
        String text = "Hello world. This is short.";
        assertEquals(text, PlaywrightCliTool.truncateAtSentence(text, 9092));
    }
}
