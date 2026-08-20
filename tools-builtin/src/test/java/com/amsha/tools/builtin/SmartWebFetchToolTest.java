package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.security.ToolOperation;
import com.amsha.core.tools.IdempotencyMode;
import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.core.tools.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SmartWebFetchToolTest {

    private SmartWebFetchTool tool;

    @BeforeEach
    void setUp() {
        tool = new SmartWebFetchTool();
    }

    @Test
    void testToolMetadataProperties() {
        assertEquals(ToolCapability.WEB_FETCH.toolName(), tool.name());
        assertNotNull(tool.description());
        assertFalse(tool.requiresSandbox());
        assertEquals(ToolOperation.NETWORK_OUTBOUND, tool.requiredOperation());
        assertEquals(IdempotencyMode.IDEMPOTENT, tool.idempotencyMode());
        assertTrue(tool.isEnabled());
        assertNotNull(tool.parametersSchema());
    }

    @Test
    void testInitAndCleanupDoesNotThrowInTestMode() {
        assertDoesNotThrow(() -> tool.init());
        assertDoesNotThrow(() -> tool.cleanup());
    }

    @Test
    void testExecuteValidation() {
        ToolExecutionContext ctx = new ToolExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "COORDINATOR",
                AutonomyLevel.FULL,
                "trace-1",
                "WEBSOCKET",
                "plat-1",
                "goal",
                null,
                null,
                null);

        ToolResponse r1 = tool.execute(ctx, Map.of());
        assertFalse(r1.success());

        ToolResponse r2 = tool.execute(ctx, Map.of("url", "ftp://example.com"));
        assertFalse(r2.success());

        ToolResponse r3 = tool.execute(ctx, Map.of("url", "http://127.0.0.1/admin"));
        assertFalse(r3.success());
        assertEquals(403, r3.exitCode());

        ToolResponse r4 = tool.execute(ctx, Map.of("url", "http://localhost:8080"));
        assertFalse(r4.success());
        assertEquals(403, r4.exitCode());
    }

    @Test
    void htmlToTextStripsTags() {
        String html = "<html><body><h1>Title</h1><p>Hello <b>world</b></p></body></html>";
        String text = SmartWebFetchTool.htmlToText(html);
        assertTrue(text.contains("Title"));
        assertTrue(text.contains("Hello world"));
        assertFalse(text.contains("<"));
    }

    @Test
    void htmlToTextRemovesScriptAndStyle() {
        String html = "<html><head><script>var x=1;</script><style>.a{color:red}</style></head>"
                + "<body><p>Visible</p></body></html>";
        String text = SmartWebFetchTool.htmlToText(html);
        assertTrue(text.contains("Visible"));
        assertFalse(text.contains("var x"));
        assertFalse(text.contains("color:red"));
    }

    @Test
    void htmlToTextDecodesEntities() {
        String html = "<p>Hello &amp; welcome &lt;user&gt;</p>";
        String text = SmartWebFetchTool.htmlToText(html);
        assertTrue(text.contains("Hello & welcome"));
        assertTrue(text.contains("<user>"));
    }

    @Test
    void htmlToTextPreservesNewlines() {
        String html = "<p>First paragraph</p><p>Second paragraph</p>";
        String text = SmartWebFetchTool.htmlToText(html);
        assertTrue(text.contains("\n"));
    }

    @Test
    void truncateAtSentencePrefersSentenceBoundary() {
        String text = "This is a short sentence. This is another sentence that goes on for a while.";
        String truncated = SmartWebFetchTool.truncateAtSentence(text, 40);
        assertTrue(truncated.endsWith("."));
        assertTrue(truncated.length() <= 40);
    }

    @Test
    void truncateAtSentenceFallsBackToHardLimit() {
        String text = "No sentence boundary here just a very long string without any punctuation marks";
        String truncated = SmartWebFetchTool.truncateAtSentence(text, 30);
        assertTrue(truncated.length() <= 30);
    }

    @Test
    void truncateAtSentenceReturnsFullTextIfShortEnough() {
        String text = "Short.";
        String truncated = SmartWebFetchTool.truncateAtSentence(text, 100);
        assertEquals(text, truncated);
    }
}
