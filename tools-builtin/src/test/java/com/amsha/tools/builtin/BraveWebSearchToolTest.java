package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.SecretVault;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S1192")
class BraveWebSearchToolTest {

    @Mock
    HttpClient httpClient;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    AuditService auditService;

    @Mock
    SecretVault vault;

    @InjectMocks
    BraveWebSearchTool tool;

    @BeforeEach
    void setup() {
        tool.configApiKey = "fake-key";
        lenient().when(vault.retrieveSystemKey("AMSHA_TOOLS_BRAVE_API_KEY")).thenReturn(Optional.empty());
        // Setup HttpClient field via reflection as Mockito @InjectMocks
        // doesn't typically run @PostConstruct
        try {
            var field = BraveWebSearchTool.class.getDeclaredField("httpClient");
            field.setAccessible(true);
            field.set(tool, httpClient);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testBlankUserIdFails() throws Exception {
        Map<String, Object> params = Map.of("query", "test");

        ToolResponse resp = WorkspaceContext.run(
                "session-1",
                "",
                "chat-1",
                AutonomyLevel.FULL,
                "RESEARCHER",
                () -> tool.execute(ctx("session-1", ""), params));

        assertEquals(403, resp.exitCode());
    }

    @Test
    void testBlankApiKeyFails() throws Exception {
        tool.configApiKey = "none";
        Map<String, Object> params = Map.of("query", "test");

        ToolResponse resp = WorkspaceContext.run(
                "session-1", "user-1", "chat-1", AutonomyLevel.FULL, "RESEARCHER", () -> tool.execute(ctx(), params));

        assertEquals(503, resp.exitCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testValidResponseFormatting() throws Exception {
        String mockBody = """
                {
                  "web": {
                    "results": [
                      { "title": "Brave Search", "url": "https://brave.com", "description": "Privacy search" }
                    ]
                  }
                }
                """;

        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn(mockBody);
        when(httpClient.<String>send(any(), any())).thenReturn(mockResp);

        Map<String, Object> params = Map.of("query", "brave");

        ToolResponse resp = WorkspaceContext.run(
                "session-1", "user-1", "chat-1", AutonomyLevel.FULL, "RESEARCHER", () -> tool.execute(ctx(), params));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("Brave Search"));
        assertTrue(resp.output().contains("https://brave.com"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testClampingOfCount() throws Exception {
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn("{\"web\":{\"results\":[]}}");
        when(httpClient.<String>send(any(), any())).thenReturn(mockResp);

        Map<String, Object> params = Map.of("query", "test", "count", 50);

        ToolResponse resp = WorkspaceContext.run(
                "session-1", "user-1", "chat-1", AutonomyLevel.FULL, "RESEARCHER", () -> tool.execute(ctx(), params));

        assertTrue(resp.output().contains("No results"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testUnauthorizedError() throws Exception {
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(401);
        when(mockResp.body()).thenReturn("Unauthorized");
        when(httpClient.<String>send(any(), any())).thenReturn(mockResp);

        Map<String, Object> params = Map.of("query", "test");

        ToolResponse resp = WorkspaceContext.run(
                "session-1", "user-1", "chat-1", AutonomyLevel.FULL, "RESEARCHER", () -> tool.execute(ctx(), params));

        assertEquals(401, resp.exitCode());
        assertTrue(resp.output().contains("Brave Search API failed"));
    }

    private ToolExecutionContext ctx() {
        return ctx("session-1", "user-1");
    }

    private ToolExecutionContext ctx(String sessionId, String userId) {
        return new ToolExecutionContext(
                sessionId,
                userId,
                "chat-1",
                "RESEARCHER",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
