package com.amsha.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.http.ApprovalReceiptIssuer;
import com.amsha.mcp.transport.McpClientService;
import com.amsha.mcp.transport.McpManagementService;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCategory;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
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

    @Mock
    McpManagementService managementService;

    @Mock
    ApprovalReceiptIssuer approvalReceiptIssuer;

    @InjectMocks
    McpSelfRegisterTool tool;

    @BeforeEach
    void setUp() {
        lenient()
                .when(approvalReceiptIssuer.issueForApprovedCheckpoint(
                        anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(Optional.of("approved-token"));
    }

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
        verify(managementService, times(1))
                .registerFromApprovedCheckpoint(
                        "user-123",
                        "my-server",
                        "http://localhost:3000/mcp",
                        Map.of(),
                        "approved-token",
                        ToolCategory.NETWORK);
        verify(auditService, times(1))
                .record(
                        eq("session-456"),
                        eq("user-123"),
                        eq("COORDINATOR"),
                        eq(SecurityEventType.TOOL_EXECUTED),
                        anyString());
    }

    @Test
    void testValidStdioRegistration() throws Exception {
        Map<String, Object> params = Map.of(
                "name", "stdio-server",
                "transport", "stdio",
                "command", "npx",
                "args", "-y, @mcp/server",
                "env", "FOO=BAR\nBAZ=QUX");

        ToolResponse resp = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(0, resp.exitCode());
        verify(mcpClientService)
                .connectStdio(
                        eq("stdio-server"),
                        eq("npx"),
                        eq(List.of("-y", "@mcp/server")),
                        eq(Map.of("FOO", "BAR", "BAZ", "QUX")),
                        eq(ToolCategory.NETWORK),
                        eq("user-123"));
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
                "name", "compute-server",
                "transport", "http",
                "url", "http://localhost:3000/mcp",
                "category", "COMPUTE");
        WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        verify(managementService)
                .registerFromApprovedCheckpoint(
                        "user-123",
                        "compute-server",
                        "http://localhost:3000/mcp",
                        Map.of(),
                        "approved-token",
                        ToolCategory.COMPUTE);
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
                .when(managementService)
                .registerFromApprovedCheckpoint(
                        eq("user-123"),
                        eq("fail-server"),
                        anyString(),
                        eq(Map.of()),
                        eq("approved-token"),
                        eq(ToolCategory.NETWORK));

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
