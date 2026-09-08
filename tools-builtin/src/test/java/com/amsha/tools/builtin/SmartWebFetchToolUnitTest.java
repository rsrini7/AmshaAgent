package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.ToolOperation;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.Test;

class SmartWebFetchToolUnitTest {

    @Test
    void testMetadata() {
        SmartWebFetchTool tool = new SmartWebFetchTool();
        assertEquals(ToolCapability.WEB_FETCH.toolName(), tool.name());
        assertTrue(tool.description().contains("Fetch a URL"));
        assertFalse(tool.requiresSandbox());
        assertEquals(ToolOperation.NETWORK_OUTBOUND, tool.requiredOperation());
        assertEquals(IdempotencyMode.IDEMPOTENT, tool.idempotencyMode());
        assertTrue(tool.isEnabled());
        assertNotNull(tool.parametersSchema());
        assertEquals(List.of("url"), tool.parametersSchema().required());
    }

    @Test
    void testHtmlToText() {
        assertEquals("", SmartWebFetchTool.htmlToText(null));
        assertEquals("", SmartWebFetchTool.htmlToText("   "));

        String html = "<script>var x = 1;</script><style>body { color: red; }</style>"
                + "<h1>Title</h1><p>First paragraph &amp; &lt;tag&gt; &quot;quote&#39;.</p>"
                + "<p>Line with &nbsp;&mdash;&ndash;&hellip;&copy; entities.</p>";
        String text = SmartWebFetchTool.htmlToText(html);
        assertTrue(text.contains("Title"));
        assertTrue(text.contains("First paragraph & <tag> \"quote'."));
        assertTrue(text.contains("—–…©"));
        assertFalse(text.contains("<script>"));
        assertFalse(text.contains("body {"));
    }

    @Test
    void testTruncateAtSentence() {
        assertEquals("Short text.", SmartWebFetchTool.truncateAtSentence("Short text.", 50));
        assertEquals(
                "Hello world. More text follows.",
                SmartWebFetchTool.truncateAtSentence("Hello world. More text follows. Extra.", 32));
        assertEquals("Question? Next part.", SmartWebFetchTool.truncateAtSentence("Question? Next part. More.", 21));
        assertEquals(
                "Exclamation! Next part.", SmartWebFetchTool.truncateAtSentence("Exclamation! Next part. More.", 24));
        assertEquals("No sentence ending here", SmartWebFetchTool.truncateAtSentence("No sentence ending here", 23));
    }

    @Test
    void testRejectPrivateHost() throws Exception {
        Method m = SmartWebFetchTool.class.getDeclaredMethod("rejectPrivateHost", String.class);
        m.setAccessible(true);

        assertNotNull(m.invoke(null, "http://127.0.0.1:8080/test"));
        assertNotNull(m.invoke(null, "http://localhost:3000/api"));
        assertNotNull(m.invoke(null, "http://10.0.0.1/admin"));
        assertNotNull(m.invoke(null, "http://192.168.1.1/secret"));
        assertNotNull(m.invoke(null, "invalid url with spaces"));
    }

    @Test
    void testValidationAndHandling() {
        SmartWebFetchTool tool = new SmartWebFetchTool();
        ToolExecutionContext ctx = new ToolExecutionContext(
                "session-1",
                "user-1",
                "chat-1",
                "coder",
                AutonomyLevel.FULL,
                "agent-1",
                "telegram",
                "plat-1",
                null,
                null,
                null,
                null);

        // Missing url parameter
        ToolResponse resp1 = tool.execute(ctx, Map.of());
        assertEquals(400, resp1.exitCode());
        assertTrue(resp1.output().contains("url"));

        // Blank url parameter
        ToolResponse resp2 = tool.execute(ctx, Map.of("url", "   "));
        assertEquals(400, resp2.exitCode());

        // Invalid scheme
        ToolResponse resp3 = tool.execute(ctx, Map.of("url", "ftp://example.com"));
        assertEquals(400, resp3.exitCode());
        assertTrue(resp3.output().contains("Unsupported URL scheme"));

        // SSRF blocked host
        ToolResponse resp4 = tool.execute(ctx, Map.of("url", "http://127.0.0.1:8080"));
        assertEquals(403, resp4.exitCode());
        assertTrue(resp4.output().contains("private/internal network"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testExecuteWithMockClient() throws Exception {
        SmartWebFetchTool tool = new SmartWebFetchTool();
        tool.auditService = mock(AuditService.class);

        GovernedTransportService mockTransport = mock(GovernedTransportService.class);
        HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
        HttpHeaders mockHeaders = mock(HttpHeaders.class);

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.uri()).thenReturn(URI.create("https://example.com/page"));
        when(mockResponse.headers()).thenReturn(mockHeaders);
        when(mockHeaders.firstValue("content-type")).thenReturn(Optional.of("text/html"));
        when(mockResponse.body())
                .thenReturn(new ByteArrayInputStream("<html><body><p>Hello from example</p></body></html>".getBytes()));

        when(mockTransport.<InputStream>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResponse);

        tool.governedTransport = mockTransport;

        ToolExecutionContext ctx = new ToolExecutionContext(
                "session-1",
                "user-1",
                "chat-1",
                "coder",
                AutonomyLevel.FULL,
                "agent-1",
                "telegram",
                "plat-1",
                null,
                null,
                null,
                null);

        ToolResponse resp = tool.execute(ctx, Map.of("url", "https://example.com/page", "maxChars", 1000));
        assertNotNull(resp);
        assertTrue(resp.success());
        assertTrue(resp.output().contains("Hello from example"));
        verify(tool.auditService).record(any(), any(), any(), any(), any());
        // HITL lives in ToolInvocationGuard; the tool must never claim transport pre-approval.
        verify(mockTransport)
                .<InputStream>send(
                        eq("https://example.com/page"),
                        eq(OutboundPurpose.TOOL_FETCH),
                        isNull(),
                        eq("user-1"),
                        eq(false),
                        any(),
                        any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testExecuteHttpErrorResponse() throws Exception {
        SmartWebFetchTool tool = new SmartWebFetchTool();
        tool.auditService = mock(AuditService.class);

        GovernedTransportService mockTransport = mock(GovernedTransportService.class);
        HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);

        when(mockResponse.statusCode()).thenReturn(404);
        when(mockResponse.uri()).thenReturn(URI.create("https://example.com/notfound"));
        when(mockResponse.body()).thenReturn(new ByteArrayInputStream("Not found".getBytes()));

        when(mockTransport.<InputStream>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResponse);

        tool.governedTransport = mockTransport;

        ToolExecutionContext ctx = new ToolExecutionContext(
                "session-1",
                "user-1",
                "chat-1",
                "coder",
                AutonomyLevel.FULL,
                "agent-1",
                "telegram",
                "plat-1",
                null,
                null,
                null,
                null);

        ToolResponse resp = tool.execute(ctx, Map.of("url", "https://example.com/notfound"));
        assertNotNull(resp);
        assertFalse(resp.success());
        assertEquals(404, resp.exitCode());
        assertTrue(resp.output().contains("HTTP 404"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testExecuteOutboundHttpException() throws Exception {
        SmartWebFetchTool tool = new SmartWebFetchTool();
        GovernedTransportService mockTransport = mock(GovernedTransportService.class);
        when(mockTransport.<InputStream>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new OutboundHttpException(
                        OutboundHttpException.Category.DNS_REBINDING, "DNS rebinding detected"));
        tool.governedTransport = mockTransport;

        ToolExecutionContext ctx = new ToolExecutionContext(
                "session-1",
                "user-1",
                "chat-1",
                "coder",
                AutonomyLevel.FULL,
                "agent-1",
                "telegram",
                "plat-1",
                null,
                null,
                null,
                null);

        ToolResponse resp = tool.execute(ctx, Map.of("url", "https://example.com/rebinding"));
        assertNotNull(resp);
        assertFalse(resp.success());
        assertEquals(403, resp.exitCode());
        assertTrue(resp.output().contains("Web fetch blocked"));
    }
}
