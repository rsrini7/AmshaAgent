package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.audit.AuditService;
import com.amsha.core.tools.IdempotencyMode;
import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.core.tools.ToolResponse;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;

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

        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
        HttpHeaders mockHeaders = mock(HttpHeaders.class);

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.uri()).thenReturn(URI.create("https://example.com/page"));
        when(mockResponse.headers()).thenReturn(mockHeaders);
        when(mockHeaders.firstValue("content-type")).thenReturn(Optional.of("text/html"));
        when(mockResponse.body())
                .thenReturn(new ByteArrayInputStream("<html><body><p>Hello from example</p></body></html>".getBytes()));

        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        Field clientField = SmartWebFetchTool.class.getDeclaredField("httpClient");
        clientField.setAccessible(true);
        clientField.set(tool, mockClient);

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
    }

    @Test
    @SuppressWarnings("unchecked")
    void testExecuteHttpErrorResponse() throws Exception {
        SmartWebFetchTool tool = new SmartWebFetchTool();
        tool.auditService = mock(AuditService.class);

        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);

        when(mockResponse.statusCode()).thenReturn(404);
        when(mockResponse.uri()).thenReturn(URI.create("https://example.com/notfound"));
        when(mockResponse.body()).thenReturn(new ByteArrayInputStream("Not found".getBytes()));

        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        Field clientField = SmartWebFetchTool.class.getDeclaredField("httpClient");
        clientField.setAccessible(true);
        clientField.set(tool, mockClient);

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
}
