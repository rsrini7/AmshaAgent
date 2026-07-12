package com.amsha.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Map;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.tools.ToolCategory;
import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.core.tools.ToolResponse;
import com.amsha.mcp.transport.McpClientService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S1192")
class McpSelfRegisterToolTest {

    @Mock
    McpClientService mcpClientService;

    @Mock
    AuditService auditService;

    @InjectMocks
    McpSelfRegisterTool tool;

    @Test
    void testValidHttpRegistration() throws Exception {
        Map<String, Object> params = Map.of(
                "name", "my-server",
                "transport", "http",
                "url", "http://localhost:3000/mcp");

        ToolResponse resp = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(0, resp.exitCode());
        verify(mcpClientService, times(1)).connect("my-server", "http://localhost:3000/mcp", ToolCategory.NETWORK);
        verify(auditService, times(1))
                .record(
                        eq("session-456"),
                        eq("user-123"),
                        eq("COORDINATOR"),
                        eq(SecurityEventType.TOOL_EXECUTED),
                        anyString());
    }

    @Test
    void testInvalidName() throws Exception {
        Map<String, Object> params = Map.of(
                "name", "invalid name!",
                "transport", "http");

        ToolResponse resp = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(400, resp.exitCode());
        assertFalse(resp.success());
    }

    @Test
    void testExplicitCategory() throws Exception {
        Map<String, Object> params = Map.of(
                "name", "database-server",
                "transport", "http",
                "url", "http://localhost:3000/mcp",
                "category", "DATABASE");

        WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        verify(mcpClientService).connect("database-server", "http://localhost:3000/mcp", ToolCategory.DATABASE);
    }

    @Test
    void testInvalidCategory() throws Exception {
        Map<String, Object> params = Map.of(
                "name", "server",
                "transport", "http",
                "url", "http://localhost:3000/mcp",
                "category", "invalid");

        ToolResponse response = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(400, response.exitCode());
    }

    @Test
    void testBlankUserIdFails() throws Exception {
        Map<String, Object> params = Map.of(
                "name", "server",
                "transport", "http",
                "url", "http://host");

        // Blank userId in run() should trigger the guard in execute()
        ToolResponse resp = WorkspaceContext.run(
                "session-456",
                "",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", ""), params));

        assertEquals(403, resp.exitCode());
    }

    @Test
    void testMcpConnectionExceptionPropagation() throws Exception {
        doThrow(new RuntimeException("Connection refused"))
                .when(mcpClientService)
                .connect(eq("fail-server"), anyString(), eq(ToolCategory.NETWORK));

        Map<String, Object> params = Map.of(
                "name", "fail-server",
                "transport", "http",
                "url", "http://localhost:3000");

        ToolResponse resp = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(502, resp.exitCode());
        assertEquals("MCP connection failed: Connection refused", resp.output());
    }

    private ToolExecutionContext ctx(String sessionId, String userId) {
        return new ToolExecutionContext(
                sessionId,
                userId,
                "chat-789",
                "COORDINATOR",
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
