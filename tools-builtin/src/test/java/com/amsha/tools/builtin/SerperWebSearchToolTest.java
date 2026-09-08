package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;
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
class SerperWebSearchToolTest {

    @Mock
    GovernedTransportService governedTransport;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    AuditService auditService;

    @Mock
    SecretVault vault;

    @InjectMocks
    SerperWebSearchTool tool;

    @BeforeEach
    void setup() {
        tool.configApiKey = "fake-serper-key";
        lenient().when(vault.retrieveSystemKey("AMSHA_TOOLS_SERPER_API_KEY")).thenReturn(Optional.empty());
        tool.governedTransport = governedTransport;
    }

    @Test
    void testBlankUserIdFails() throws Exception {
        Map<String, Object> params = Map.of("query", "test");

        ToolResponse resp = tool.execute(ctx("session-1", ""), params);

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
        when(governedTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResp);

        Map<String, Object> params = Map.of("query", "ultimate answer");

        ToolResponse resp = WorkspaceContext.run(
                "session-1", "user-1", "chat-1", AutonomyLevel.FULL, "RESEARCHER", () -> tool.execute(ctx(), params));

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
        when(governedTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResp);

        Map<String, Object> params = Map.of("query", "quarkus");

        ToolResponse resp = WorkspaceContext.run(
                "session-1", "user-1", "chat-1", AutonomyLevel.FULL, "RESEARCHER", () -> tool.execute(ctx(), params));

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
        when(governedTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResp);

        Map<String, Object> params = Map.of("query", "test");

        ToolResponse resp = WorkspaceContext.run(
                "session-1", "user-1", "chat-1", AutonomyLevel.FULL, "RESEARCHER", () -> tool.execute(ctx(), params));

        assertEquals(401, resp.exitCode());
        assertTrue(resp.output().contains("Serper Search API failed"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testCountClamping() throws Exception {
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn("{\"organic\":[]}");
        when(governedTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResp);

        Map<String, Object> params = Map.of("query", "test", "count", 50);

        ToolResponse resp = WorkspaceContext.run(
                "session-1", "user-1", "chat-1", AutonomyLevel.FULL, "RESEARCHER", () -> tool.execute(ctx(), params));

        assertTrue(resp.output().contains("No relevant web or news results"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testOutboundHttpExceptionPolicyDenied() throws Exception {
        when(governedTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new OutboundHttpException(OutboundHttpException.Category.POLICY_DENIED, "Policy violation"));

        ToolResponse resp = tool.execute(ctx(), Map.of("query", "test"));
        assertEquals(403, resp.exitCode());
        assertTrue(resp.output().contains("Serper search blocked"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testTransportReceivesPreApprovedFalse() throws Exception {
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn("{\"organic\":[]}");
        when(governedTransport.<String>send(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(mockResp);

        Map<String, Object> params = Map.of("query", "quarkus");
        WorkspaceContext.run(
                "session-1", "user-1", "chat-1", AutonomyLevel.FULL, "RESEARCHER", () -> tool.execute(ctx(), params));

        // HITL lives in ToolInvocationGuard; the tool must never claim transport pre-approval.
        verify(governedTransport)
                .<String>send(
                        anyString(), eq(OutboundPurpose.WEB_SEARCH), isNull(), eq("user-1"), eq(false), any(), any());
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
