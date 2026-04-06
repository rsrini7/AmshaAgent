package com.arcclaw.tool.playwright;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.scope.WorkspaceContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaywrightBrowserToolTest {

    @Mock PlaywrightPageRunner pageRunner;
    @Mock AuditService auditService;

    PlaywrightBrowserTool tool;

    @BeforeEach
    void setUp() {
        tool = new PlaywrightBrowserTool();
        tool.enabled = true;
        tool.headless = true;
        tool.timeoutMs = 5000;
        tool.pageRunner = pageRunner;
        tool.auditService = auditService;
    }

    private ToolResponse runWithContext(Map<String, Object> params) {
        try {
            return WorkspaceContext.run("sess-1", "test-user-123", "chat-1", AutonomyLevel.FULL, "researcher", () -> tool.execute(params));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Disabled state ────────────────────────────────────────────────────────
    @Test void isEnabled_returnsFalse_whenConfigFalse() {
        tool.enabled = false;
        Assertions.assertFalse(tool.isEnabled());
    }

    @Test void execute_returns503_whenToolDisabled() {
        tool.enabled = false;
        Map<String, Object> params = Map.of("url", "https://example.com");
        ToolResponse res = runWithContext(params);
        Assertions.assertFalse(res.success());
        Assertions.assertEquals(503, res.exitCode());
    }

    // ── Input validation ──────────────────────────────────────────────────────
    @Test void execute_returns400_whenUrlBlank() {
        Map<String, Object> params = Map.of("url", "   ");
        ToolResponse res = runWithContext(params);
        Assertions.assertFalse(res.success());
        Assertions.assertEquals(400, res.exitCode());
    }

    @Test void execute_returns400_whenUrlNull() {
        Map<String, Object> params = new HashMap<>(); // allows null
        ToolResponse res = runWithContext(params);
        Assertions.assertFalse(res.success());
        Assertions.assertEquals(400, res.exitCode());
    }

    @Test void execute_returns400_whenJavascriptScheme() {
        Map<String, Object> params = Map.of("url", "javascript:alert(1)");
        ToolResponse res = runWithContext(params);
        Assertions.assertFalse(res.success());
        Assertions.assertEquals(400, res.exitCode());
    }

    @Test void execute_returns400_whenFileScheme() {
        Map<String, Object> params = Map.of("url", "file:///etc/passwd");
        ToolResponse res = runWithContext(params);
        Assertions.assertFalse(res.success());
        Assertions.assertEquals(400, res.exitCode());
    }

    @Test void execute_returns400_whenFtpScheme() {
        Map<String, Object> params = Map.of("url", "ftp://host/file");
        ToolResponse res = runWithContext(params);
        Assertions.assertFalse(res.success());
        Assertions.assertEquals(400, res.exitCode());
    }

    @Test void execute_returns403_whenUserIdBlank() throws Exception {
        Map<String, Object> params = Map.of("url", "https://example.com");
        ToolResponse res = WorkspaceContext.run("sess-1", "", "chat-1", AutonomyLevel.FULL, "researcher", () -> tool.execute(params));
        Assertions.assertFalse(res.success());
        Assertions.assertEquals(403, res.exitCode());
    }

    // ── Happy path ────────────────────────────────────────────────────────────
    @Test void execute_callsRunnerWithCorrectArgs_extractText() {
        Map<String, Object> params = new HashMap<>();
        params.put("url", "https://example.com");
        params.put("action", "extract_text");
        params.put("selector", null);

        when(pageRunner.run(anyString(), anyString(), isNull(), anyBoolean(), anyInt())).thenReturn("content");

        ToolResponse res = runWithContext(params);

        verify(pageRunner).run("https://example.com", "extract_text", null, true, 5000);
        Assertions.assertTrue(res.success());
    }

    @Test void execute_callsRunnerWithSelector() {
        Map<String, Object> params = Map.of("url", "https://example.com", "action", "extract_text", "selector", "h1");

        when(pageRunner.run(anyString(), anyString(), anyString(), anyBoolean(), anyInt())).thenReturn("content");

        runWithContext(params);

        verify(pageRunner).run("https://example.com", "extract_text", "h1", true, 5000);
    }

    @Test void execute_defaultsActionToExtractText_whenAbsent() {
        Map<String, Object> params = Map.of("url", "https://example.com");

        when(pageRunner.run(anyString(), anyString(), isNull(), anyBoolean(), anyInt())).thenReturn("content");

        runWithContext(params);

        verify(pageRunner).run("https://example.com", "extract_text", null, true, 5000);
    }

    // ── Error handling ────────────────────────────────────────────────────────
    @Test void execute_returns502_whenRunnerThrows() {
        Map<String, Object> params = Map.of("url", "https://example.com");

        when(pageRunner.run(anyString(), anyString(), isNull(), anyBoolean(), anyInt()))
            .thenThrow(new RuntimeException("connection refused"));

        ToolResponse res = runWithContext(params);

        Assertions.assertFalse(res.success());
        Assertions.assertEquals(502, res.exitCode());
    }

    // ── Truncation ────────────────────────────────────────────────────────────
    @Test void execute_truncatesTextResultOver9092Chars() {
        Map<String, Object> params = Map.of("url", "https://example.com");

        String longResult = "a".repeat(10000) + ". end of content.";
        when(pageRunner.run(anyString(), anyString(), isNull(), anyBoolean(), anyInt())).thenReturn(longResult);

        ToolResponse res = runWithContext(params);

        Assertions.assertTrue(res.output().endsWith("[content truncated]"));
        Assertions.assertTrue(res.output().length() <= 9092 + "[content truncated]".length() + 1);
    }

    @Test void execute_doesNotTruncateResultUnder9092Chars() {
        Map<String, Object> params = Map.of("url", "https://example.com");

        when(pageRunner.run(anyString(), anyString(), isNull(), anyBoolean(), anyInt())).thenReturn("short text");

        ToolResponse res = runWithContext(params);

        Assertions.assertEquals("short text", res.output());
    }

    @Test void execute_doesNotTruncate_screenshotResult() {
        Map<String, Object> params = Map.of("url", "https://example.com", "action", "screenshot");

        String longResult = "a".repeat(10000);
        when(pageRunner.run(anyString(), anyString(), isNull(), anyBoolean(), anyInt())).thenReturn(longResult);

        ToolResponse res = runWithContext(params);

        Assertions.assertEquals(longResult, res.output());
    }
}
