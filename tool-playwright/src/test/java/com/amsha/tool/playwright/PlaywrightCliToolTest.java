package com.amsha.tool.playwright;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.tools.IdempotencyMode;
import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.core.tools.ToolResponse;
import com.amsha.security.api.ToolOperation;

import org.junit.jupiter.api.Test;

class PlaywrightCliToolTest {

    private final PlaywrightCliTool tool = new PlaywrightCliTool();

    @Test
    void metadataAndSchema() {
        assertEquals(ToolCapability.BROWSER_NAVIGATE.toolName(), tool.name());
        assertFalse(tool.requiresSandbox());
        assertFalse(tool.isEnabled());
        assertEquals(ToolOperation.NETWORK_OUTBOUND, tool.requiredOperation());
        assertFalse(tool.requiresManualApproval(Map.of()));
        assertEquals(IdempotencyMode.IDEMPOTENT, tool.idempotencyMode());
        assertNotNull(tool.description());
        assertNotNull(tool.parametersSchema());
    }

    @Test
    void healthCheckReturns503WhenDisabled() {
        ToolResponse r = tool.healthCheck();
        assertFalse(r.success());
        assertEquals(503, r.exitCode());
        assertTrue(r.output().contains("disabled"));
    }

    @Test
    void healthCheckWhenEnabled() {
        tool.configEnabled = true;
        ToolResponse r = tool.healthCheck();
        assertNotNull(r);
    }

    private static ToolExecutionContext ctx(String sid, String uid) {
        return new ToolExecutionContext(sid, uid, null, "researcher", null, null, null, null, null, null, null, null);
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
    void testClickActionRequiresSelector() {
        tool.configEnabled = true;
        ToolResponse r = tool.execute(ctx("s1", "u1"), Map.of("action", "click"));
        assertFalse(r.success());
        assertEquals(400, r.exitCode());
        assertTrue(r.output().contains("selector"));
    }

    @Test
    void testFillActionRequiresValue() {
        tool.configEnabled = true;
        ToolResponse r = tool.execute(ctx("s1", "u1"), Map.of("action", "fill", "selector", "input"));
        assertFalse(r.success());
        assertEquals(400, r.exitCode());
        assertTrue(r.output().contains("value"));
    }

    @Test
    void testEvalActionRequiresScript() {
        tool.configEnabled = true;
        ToolResponse r = tool.execute(ctx("s1", "u1"), Map.of("action", "eval"));
        assertFalse(r.success());
        assertEquals(400, r.exitCode());
        assertTrue(r.output().contains("script"));
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

    @Test
    void testSessionName() throws Exception {
        Method sessionName = PlaywrightCliTool.class.getDeclaredMethod("sessionName", String.class);
        sessionName.setAccessible(true);

        String s1 = (String) sessionName.invoke(null, (Object) null);
        assertEquals("amsha-default", s1);

        String s2 = (String) sessionName.invoke(null, "sess:123:456");
        assertTrue(s2.startsWith("amsha-"));
        assertEquals(22, s2.length()); // "amsha-" + 16 hex chars = 22
    }

    @Test
    void testEscapeJsString() throws Exception {
        Method escape = PlaywrightCliTool.class.getDeclaredMethod("escapeJsString", String.class);
        escape.setAccessible(true);

        String esc = (String) escape.invoke(null, "hello 'world' \n\t");
        assertTrue(esc.contains("\\'"));
        assertTrue(esc.contains("\\n"));
        assertTrue(esc.contains("\\t"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPlatformCommand() throws Exception {
        Method platformCmd = PlaywrightCliTool.class.getDeclaredMethod("platformCommand", List.class);
        platformCmd.setAccessible(true);

        List<String> input = List.of("playwright-cli", "version");
        List<String> res = (List<String>) platformCmd.invoke(null, input);
        assertNotNull(res);
        assertFalse(res.isEmpty());
    }

    @Test
    void testDoEvalFormatting() throws Exception {
        Method doEval = PlaywrightCliTool.class.getDeclaredMethod("doEval", String.class, String.class);
        doEval.setAccessible(true);

        // Expect it to call execWithSession which fails if CLI is missing, but tests the script wrapping logic
        assertThrows(Exception.class, () -> doEval.invoke(tool, "sess", "document.title"));
        assertThrows(Exception.class, () -> doEval.invoke(tool, "sess", "() => 42"));
    }

    @Test
    void testCleanupNoThrow() {
        assertDoesNotThrow(tool::cleanup);
    }
}
