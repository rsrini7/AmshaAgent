package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Map;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SerperWebSearchToolTest {

    @Mock
    HttpClient httpClient;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    AuditService auditService;

    @InjectMocks
    SerperWebSearchTool tool;

    @BeforeEach
    void setup() {
        tool.apiKey = "fake-serper-key";
        try {
            var field = SerperWebSearchTool.class.getDeclaredField("httpClient");
            field.setAccessible(true);
            field.set(tool, httpClient);
        } catch (Exception e) {
             throw new RuntimeException(e);
        }
    }

    @Test
    void testBlankUserIdFails() throws Exception {
        Map<String, Object> params = Map.of("query", "test");

        ToolResponse resp = WorkspaceContext.run("session-1", "", "chat-1", 
            AutonomyLevel.FULL, "RESEARCHER", () -> tool.execute(params));

        assertEquals(403, resp.exitCode());
    }

    @Test
    void testBlankApiKeyFails() throws Exception {
        tool.apiKey = "none";
        Map<String, Object> params = Map.of("query", "test");

        ToolResponse resp = WorkspaceContext.run("session-1", "user-1", "chat-1", 
            AutonomyLevel.FULL, "RESEARCHER", () -> tool.execute(params));

        assertEquals(503, resp.exitCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testValidResponseWithAnswerBox() throws Exception {
        String mockBody = """
                {
                  "answerBox": { "answer": "42" },
                  "organic": [
                    { "title": "Result 1", "link": "https://example.com", "snippet": "A snippet." }
                  ]
                }
                """;
        
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn(mockBody);
        when(httpClient.<String>send(any(), any())).thenReturn(mockResp);

        Map<String, Object> params = Map.of("query", "ultimate answer");

        ToolResponse resp = WorkspaceContext.run("session-1", "user-1", "chat-1", 
            AutonomyLevel.FULL, "RESEARCHER", () -> tool.execute(params));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("Direct Answer"));
        assertTrue(resp.output().contains("42"));
        assertTrue(resp.output().contains("Result 1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testValidOrganicOnly() throws Exception {
        String mockBody = """
                {
                  "organic": [
                    { "title": "Quarkus Docs", "link": "https://quarkus.io", "snippet": "Fast Java." }
                  ]
                }
                """;
        
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn(mockBody);
        when(httpClient.<String>send(any(), any())).thenReturn(mockResp);

        Map<String, Object> params = Map.of("query", "quarkus");

        ToolResponse resp = WorkspaceContext.run("session-1", "user-1", "chat-1", 
            AutonomyLevel.FULL, "RESEARCHER", () -> tool.execute(params));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("Quarkus Docs"));
        assertTrue(resp.output().contains("https://quarkus.io"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testApiErrorPropagated() throws Exception {
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(401);
        when(mockResp.body()).thenReturn("Unauthorized");
        when(httpClient.<String>send(any(), any())).thenReturn(mockResp);

        Map<String, Object> params = Map.of("query", "test");

        ToolResponse resp = WorkspaceContext.run("session-1", "user-1", "chat-1", 
            AutonomyLevel.FULL, "RESEARCHER", () -> tool.execute(params));

        assertEquals(401, resp.exitCode());
        assertTrue(resp.output().contains("Serper Search API failed"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testCountClamping() throws Exception {
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn("{\"organic\":[]}");
        when(httpClient.<String>send(any(), any())).thenReturn(mockResp);

        Map<String, Object> params = Map.of("query", "test", "count", 50);

        ToolResponse resp = WorkspaceContext.run("session-1", "user-1", "chat-1", 
            AutonomyLevel.FULL, "RESEARCHER", () -> tool.execute(params));
        
        assertTrue(resp.output().contains("No relevant web or news results"));
    }
}
