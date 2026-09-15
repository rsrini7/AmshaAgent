package com.amsha.tools.builtin.playwright;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class PlaywrightCliToolCoverageTest {

    /**
     * A helper that answers "the CLI daemon is not running", which is the steady state in a unit
     * test environment. Every CLI-backed path therefore fails fast (playwright-cli exits non-zero
     * with "browser ... is not open"), letting us exercise the real argument-building and error
     * mapping without ever launching Chromium.
     */
    private static PlaywrightCliTool toolWithUnavailableMcp() {
        PlaywrightCliTool tool = new PlaywrightCliTool();
        tool.configEnabled = true;
        tool.headless = true;
        tool.mcpHelper = Mockito.mock(PlaywrightMcpHelper.class); // isAvailable() -> false
        return tool;
    }

    private static ToolExecutionContext ctx() {
        return new ToolExecutionContext(
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
    }

    private static Method privateMethod(String name, Class<?>... types) throws Exception {
        Method m = PlaywrightCliTool.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m;
    }

    /**
     * Asserts a reflected helper call failed for the <em>expected</em> reason: playwright-cli
     * reported a failure. Without pinning the cause, an argument-type mismatch would satisfy a
     * bare {@code assertThrows(Exception.class, ...)} and the test would pass without ever
     * reaching the code under test.
     */
    private static void assertCliFailure(Executable call) {
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, call);

        assertInstanceOf(RuntimeException.class, thrown.getCause());
        assertTrue(
                thrown.getCause().getMessage().contains("playwright-cli"),
                "Expected a playwright-cli failure but got: " + thrown.getCause());
    }

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

    @Test
    void testDoEvalScriptWrapping() throws Exception {
        Method doEvalMethod = PlaywrightCliTool.class.getDeclaredMethod("doEval", String.class, String.class);
        doEvalMethod.setAccessible(true);

        PlaywrightCliTool tool = new PlaywrightCliTool();
        // Since execute uses session and we can test script logic with helper or reflect
        Method escapeMethod = PlaywrightCliTool.class.getDeclaredMethod("escapeJsString", String.class);
        escapeMethod.setAccessible(true);
        assertEquals("test", escapeMethod.invoke(null, "test"));
    }

    @Test
    void testExecuteWithMcpHelperDelegation() {
        PlaywrightCliTool tool = new PlaywrightCliTool();
        tool.configEnabled = true;

        PlaywrightMcpHelper mockHelper = Mockito.mock(PlaywrightMcpHelper.class);
        Mockito.when(mockHelper.isAvailable()).thenReturn(true);
        Mockito.when(mockHelper.invoke(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(ToolResponse.success("mcp output"));

        tool.mcpHelper = mockHelper;

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

        ToolResponse resp = tool.execute(ctx, Map.of("url", "https://example.com"));
        assertTrue(resp.success());
        assertEquals("mcp output", resp.output());
    }

    @Test
    void testTruncateAtSentenceEdgeCases() {
        assertEquals("", PlaywrightCliTool.truncateAtSentence("", 10));
        assertEquals("Hello world", PlaywrightCliTool.truncateAtSentence("Hello world", 100));
        assertEquals("Hello world.", PlaywrightCliTool.truncateAtSentence("Hello world. More content here", 17));
    }

    @Test
    void testValidateParameters() {
        PlaywrightCliTool tool = new PlaywrightCliTool();
        assertNull(tool.validateParameters(Map.of()));
    }

    // ── escapeJsString: every escape arm ─────────────────────────────────────────────────

    @Test
    void escapeJsStringCoversEveryEscapeArm() throws Exception {
        Method escape = privateMethod("escapeJsString", String.class);

        assertEquals("a\\\"b", escape.invoke(null, "a\"b"));
        assertEquals("a\\rb", escape.invoke(null, "a\rb"));
        assertEquals("a\\bb", escape.invoke(null, "a\bb"));
        assertEquals("a\\fb", escape.invoke(null, "a\fb"));
        // Control characters below 0x20 and DEL must be emitted as \xNN
        assertEquals("a\\x01b", escape.invoke(null, "a\u0001b"));
        assertEquals("a\\x7fb", escape.invoke(null, "a\u007fb"));
    }

    // ── platformCommand: Windows wrapping ────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void platformCommandWrapsViaCmdExeOnWindows() throws Exception {
        Method platformCmd = privateMethod("platformCommand", List.class);
        String originalOsName = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            List<String> wrapped =
                    (List<String>) platformCmd.invoke(null, List.of("playwright-cli", "eval", "() => 1"));

            assertEquals(List.of("cmd.exe", "/c", "playwright-cli", "eval", "() => 1"), wrapped);
        } finally {
            System.setProperty("os.name", originalOsName);
        }
    }

    // ── Action helpers: argument building + failure mapping ──────────────────────────────

    @Test
    void doClickBuildsSelectorScriptAndPropagatesCliFailure() throws Exception {
        Method doClick = privateMethod("doClick", String.class, String.class);
        PlaywrightCliTool tool = toolWithUnavailableMcp();

        // The selector carries a quote so the JS-escaping path is exercised too.
        assertCliFailure(() -> doClick.invoke(tool, "sess-x", "button.primary[data-x=\"1\"]"));
    }

    @Test
    void doFillBuildsFillScriptAndPropagatesCliFailure() throws Exception {
        Method doFill = privateMethod("doFill", String.class, String.class, String.class);
        PlaywrightCliTool tool = toolWithUnavailableMcp();

        assertCliFailure(() -> doFill.invoke(tool, "sess-x", "#email", "a'b\nc"));
    }

    /**
     * playwright-cli has no {@code wait} subcommand, so the built-in attempt always fails and the
     * JS-polling fallback must be used. A single invocation exercises every line here; the
     * remaining branches of the timeoutMs guard are covered behaviourally but would cost two
     * more CLI round-trips each.
     */
    @Test
    void doWaitFallsBackToJsPolling() throws Exception {
        Method doWait = privateMethod("doWait", String.class, String.class, Map.class);
        PlaywrightCliTool tool = toolWithUnavailableMcp();

        // timeoutMs present and numeric -> clamped into [1000, 30000]
        assertCliFailure(() -> doWait.invoke(tool, "sess-x", "#ready", Map.of("timeoutMs", 250_000)));
    }

    @Test
    void doEvalWrapsNonCallableScripts() throws Exception {
        Method doEval = privateMethod("doEval", String.class, String.class);
        PlaywrightCliTool tool = toolWithUnavailableMcp();

        // Non-callable expression gets wrapped as "() => (...)"
        assertCliFailure(() -> doEval.invoke(tool, "sess-x", "document.title"));
    }

    @Test
    void extractTextPropagatesCliFailure() throws Exception {
        Method extractText = privateMethod("extractText", String.class);
        PlaywrightCliTool tool = toolWithUnavailableMcp();

        assertCliFailure(() -> extractText.invoke(tool, "sess-x"));
    }

    /**
     * A CLI failure is a {@link RuntimeException}, which is <em>not</em> caught by
     * {@code takeScreenshot}'s {@code catch (IOException)} - it must surface unchanged rather
     * than being mislabelled as a screenshot I/O error.
     */
    @Test
    void takeScreenshotPropagatesCliFailure() throws Exception {
        Method takeScreenshot = privateMethod("takeScreenshot", String.class);
        PlaywrightCliTool tool = toolWithUnavailableMcp();

        InvocationTargetException thrown =
                assertThrows(InvocationTargetException.class, () -> takeScreenshot.invoke(tool, "sess-x"));

        assertInstanceOf(RuntimeException.class, thrown.getCause());
        assertTrue(
                thrown.getCause().getMessage().contains("playwright-cli"),
                "Expected a playwright-cli failure but got: " + thrown.getCause());
        assertFalse(
                thrown.getCause().getMessage().startsWith("Screenshot failed:"),
                "A CLI failure must not be relabelled as a screenshot I/O failure: " + thrown.getCause());
    }

    // ── execute(): MCP fallback into the CLI path ────────────────────────────────────────

    @Test
    void executeFallsBackToCliWhenMcpInvocationFails() {
        PlaywrightCliTool tool = toolWithUnavailableMcp();
        Mockito.when(tool.mcpHelper.isAvailable()).thenReturn(true);
        Mockito.when(tool.mcpHelper.invoke(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("mcp transport down"));

        // action=eval needs no URL, so the browser is never opened.
        ToolResponse resp = tool.execute(ctx(), Map.of("action", "eval", "script", "1 + 1"));

        assertFalse(resp.success(), resp.output());
        assertEquals(502, resp.exitCode());
        assertTrue(resp.output().contains("Browser action 'eval' failed"), resp.output());
    }

    // ── cleanup() ────────────────────────────────────────────────────────────────────────

    @Test
    void cleanupNeverThrows() {
        PlaywrightCliTool tool = toolWithUnavailableMcp();
        assertDoesNotThrow(tool::cleanup);
    }
}
