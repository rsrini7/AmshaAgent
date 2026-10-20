package com.amsha.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.mcp.transport.McpClientService;
import com.amsha.mcp.transport.McpManagementService;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
import com.amsha.security.http.ApprovalReceiptIssuer;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolCategory;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    // ---------------------------------------------------------------- metadata

    @Test
    void exposesTheRegisterCapabilityMetadata() {
        assertEquals("register_mcp_server", tool.name());
        assertEquals(ToolCapability.REGISTER_MCP_SERVER.toolName(), tool.name());
        assertEquals(ToolCapability.REGISTER_MCP_SERVER.description(), tool.description());
    }

    @Test
    void declaresItsGovernancePosture() {
        assertFalse(tool.requiresSandbox(), "transport wiring itself needs no sandbox");
        assertEquals(ToolOperation.NETWORK_OUTBOUND, tool.requiredOperation());
        assertTrue(
                tool.requiresManualApproval(Map.of()),
                "registering a server grants new capabilities, so approval is unconditional");
    }

    // ------------------------------------------------------------------ schema

    @Test
    void parametersSchemaDeclaresEveryRegistrationParameter() {
        JsonObjectSchema schema = tool.parametersSchema();

        assertNotNull(schema);
        assertEquals(List.of("name", "transport"), schema.required(), "only name and transport are mandatory");
        assertTrue(
                schema.properties()
                        .keySet()
                        .containsAll(
                                List.of("name", "transport", "url", "headers", "command", "args", "env", "category")),
                () -> "every documented parameter must be exposed, saw "
                        + schema.properties().keySet());
    }

    @Test
    void parametersSchemaConstrainsTransportAndCategoryToEnumerations() {
        JsonObjectSchema schema = tool.parametersSchema();

        JsonEnumSchema transport =
                assertInstanceOf(JsonEnumSchema.class, schema.properties().get("transport"));
        assertEquals(List.of("http", "stdio"), transport.enumValues());

        JsonEnumSchema category =
                assertInstanceOf(JsonEnumSchema.class, schema.properties().get("category"));
        assertEquals(List.of("NETWORK", "COMPUTE", "DATABASE", "INTERNAL"), category.enumValues());
    }

    @Test
    void parametersSchemaDescribesHeadersAsAnArrayOfNameValueObjects() {
        JsonObjectSchema schema = tool.parametersSchema();

        JsonArraySchema headers =
                assertInstanceOf(JsonArraySchema.class, schema.properties().get("headers"));
        JsonObjectSchema entry = assertInstanceOf(JsonObjectSchema.class, headers.items());
        assertEquals(List.of("name", "value"), entry.required());
    }

    // ------------------------------------------------------------- happy paths

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
    void httpRegistrationForwardsEveryParsedHeader() throws Exception {
        Map<String, Object> params = Map.of(
                "name", "headered",
                "transport", "http",
                "url", "http://localhost:3000/mcp",
                "headers",
                        List.of(
                                Map.of("name", "Authorization", "value", "Bearer x"),
                                Map.of("name", "X-Trace", "value", "t-1")));

        ToolResponse resp = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(0, resp.exitCode());
        verify(managementService)
                .registerFromApprovedCheckpoint(
                        "user-123",
                        "headered",
                        "http://localhost:3000/mcp",
                        Map.of("Authorization", "Bearer x", "X-Trace", "t-1"),
                        "approved-token",
                        ToolCategory.NETWORK);
    }

    @Test
    void stdioRegistrationWithoutArgsOrEnvFallsBackToEmptyCollections() throws Exception {
        Map<String, Object> params = Map.of(
                "name", "bare-stdio",
                "transport", "stdio",
                "command", "python");

        ToolResponse resp = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(0, resp.exitCode());
        verify(mcpClientService)
                .connectStdio("bare-stdio", "python", List.of(), Map.of(), ToolCategory.NETWORK, "user-123");
    }

    @Test
    void stdioArgsAreTrimmedAndBlankSegmentsDropped() throws Exception {
        Map<String, Object> params = Map.of(
                "name", "trimmed",
                "transport", "stdio",
                "command", "npx",
                "args", "a, ,b,");

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
                        eq("trimmed"),
                        eq("npx"),
                        eq(List.of("a", "b")),
                        eq(Map.of()),
                        eq(ToolCategory.NETWORK),
                        eq("user-123"));
    }

    @Test
    void stdioArgsThatAreOnlyWhitespaceAreTreatedAsAbsent() throws Exception {
        Map<String, Object> params = Map.of(
                "name", "blank-args",
                "transport", "stdio",
                "command", "npx",
                "args", "   ");

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
                        eq("blank-args"),
                        eq("npx"),
                        eq(List.of()),
                        eq(Map.of()),
                        eq(ToolCategory.NETWORK),
                        eq("user-123"));
    }

    @Test
    void stdioEnvLinesWithoutAKeyValueSeparatorAreIgnored() throws Exception {
        Map<String, Object> params = Map.of(
                "name", "env-server",
                "transport", "stdio",
                "command", "npx",
                "env", "GOOD=1\nNOEQUALS\n=LEADING\nTRAILING=");

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
                        eq("env-server"),
                        eq("npx"),
                        eq(List.of()),
                        eq(Map.of("GOOD", "1", "TRAILING", "")),
                        eq(ToolCategory.NETWORK),
                        eq("user-123"));
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
    void aLowercaseCategoryNameIsAccepted() throws Exception {
        Map<String, Object> params = Map.of(
                "name", "lower-case",
                "transport", "http",
                "url", "http://localhost:3000/mcp",
                "category", "database");
        WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        // DATABASE parses, but has no safe default operation, so registration is refused.
        verifyNoInteractions(managementService);
    }

    // ------------------------------------------------------------ tenant guard

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
    void aNullUserIdIsRejected() throws Exception {
        ToolResponse resp = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", null), Map.of("name", "server", "transport", "http")));

        assertEquals(403, resp.exitCode());
        assertEquals("Security violation: tenant context not bound.", resp.output());
        verifyNoInteractions(managementService, mcpClientService, auditService);
    }

    @Test
    void aNullSessionIdIsRejected() throws Exception {
        ToolResponse resp = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx(null, "user-123"), Map.of("name", "server", "transport", "http")));

        assertEquals(403, resp.exitCode());
        verifyNoInteractions(managementService, mcpClientService, auditService);
    }

    // -------------------------------------------------------------- validation

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
    void aMissingNameIsRejected() throws Exception {
        ToolResponse resp = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), Map.of("transport", "http")));

        assertEquals(400, resp.exitCode());
        assertTrue(resp.output().startsWith("Invalid name."), resp.output());
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
    void aNonStringCategoryIsRejected() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("name", "server");
        params.put("transport", "http");
        params.put("category", 42);

        ToolResponse response = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(400, response.exitCode());
        assertTrue(response.output().startsWith("Invalid category."), response.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DATABASE", "INTERNAL"})
    void aCategoryWithNoSafeDefaultOperationIsRejected(String category) throws Exception {
        Map<String, Object> params = Map.of(
                "name", "server",
                "transport", "http",
                "url", "http://localhost:3000/mcp",
                "category", category);

        ToolResponse response = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(400, response.exitCode());
        assertEquals("category " + category + " requires an explicit ToolOperation at registration", response.output());
        verifyNoInteractions(managementService, mcpClientService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void httpTransportRequiresANonBlankUrl(String url) throws Exception {
        Map<String, Object> params = Map.of("name", "server", "transport", "http", "url", url);

        ToolResponse response = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(400, response.exitCode());
        assertEquals("url is required for http transport.", response.output());
    }

    @Test
    void httpTransportRejectsAnAbsentUrl() throws Exception {
        ToolResponse response = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), Map.of("name", "server", "transport", "http")));

        assertEquals(400, response.exitCode());
        assertEquals("url is required for http transport.", response.output());
    }

    @Test
    void stdioTransportRequiresACommand() throws Exception {
        ToolResponse response = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), Map.of("name", "server", "transport", "stdio")));

        assertEquals(400, response.exitCode());
        assertEquals("command is required for stdio transport.", response.output());
    }

    @Test
    void stdioTransportRejectsABlankCommand() throws Exception {
        Map<String, Object> params = Map.of("name", "server", "transport", "stdio", "command", "   ");

        ToolResponse response = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(400, response.exitCode());
        assertEquals("command is required for stdio transport.", response.output());
        verifyNoInteractions(mcpClientService);
    }

    @Test
    void stdioEnvThatIsOnlyWhitespaceIsTreatedAsAbsent() throws Exception {
        Map<String, Object> params = Map.of(
                "name", "blank-env",
                "transport", "stdio",
                "command", "npx",
                "env", "   ");

        ToolResponse response = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(0, response.exitCode());
        verify(mcpClientService)
                .connectStdio(
                        eq("blank-env"),
                        eq("npx"),
                        eq(List.of()),
                        eq(Map.of()),
                        eq(ToolCategory.NETWORK),
                        eq("user-123"));
    }

    @Test
    void anUnsupportedTransportIsRejected() throws Exception {
        ToolResponse response = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), Map.of("name", "server", "transport", "sse")));

        assertEquals(400, response.exitCode());
        assertEquals("Unsupported transport: sse", response.output());
        verifyNoInteractions(managementService, mcpClientService);
    }

    // ------------------------------------------------------------- header shape

    @Test
    void headersMustBeAnArray() throws Exception {
        Map<String, Object> params =
                Map.of("name", "server", "transport", "http", "url", "http://host", "headers", "not-a-list");

        ToolResponse response = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(400, response.exitCode());
        assertEquals("Invalid MCP registration: headers must be an array of {name,value} objects.", response.output());
    }

    @Test
    void headerEntriesMustBeObjects() throws Exception {
        Map<String, Object> params =
                Map.of("name", "server", "transport", "http", "url", "http://host", "headers", List.of("oops"));

        ToolResponse response = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(400, response.exitCode());
        assertEquals("Invalid MCP registration: headers entries must be objects.", response.output());
    }

    @Test
    void headerEntriesRequireAStringName() throws Exception {
        Map<String, Object> params = Map.of(
                "name",
                "server",
                "transport",
                "http",
                "url",
                "http://host",
                "headers",
                List.of(Map.of("name", 1, "value", "v")));

        ToolResponse response = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(400, response.exitCode());
        assertEquals("Invalid MCP registration: headers entries require string name and value.", response.output());
    }

    @Test
    void headerEntriesRequireAStringValue() throws Exception {
        Map<String, Object> params = Map.of(
                "name",
                "server",
                "transport",
                "http",
                "url",
                "http://host",
                "headers",
                List.of(Map.of("name", "n", "value", 2)));

        ToolResponse response = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(400, response.exitCode());
        assertEquals("Invalid MCP registration: headers entries require string name and value.", response.output());
    }

    // ------------------------------------------------------------ failure paths

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

    @Test
    void aMissingApprovedCheckpointIsReportedAsAConnectionFailure() throws Exception {
        lenient()
                .when(approvalReceiptIssuer.issueForApprovedCheckpoint(
                        anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> params = Map.of(
                "name", "no-approval",
                "transport", "http",
                "url", "http://localhost:3000/mcp");

        ToolResponse resp = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(502, resp.exitCode());
        assertEquals("MCP connection failed: Approved checkpoint is required for MCP registration", resp.output());
        verifyNoInteractions(managementService);
    }

    @Test
    void aMalformedUrlIsReportedAsAnInvalidRegistration() throws Exception {
        Map<String, Object> params = Map.of(
                "name", "bad-url",
                "transport", "http",
                "url", "ht tp://host");

        ToolResponse resp = WorkspaceContext.run(
                "session-456",
                "user-123",
                "chat-789",
                AutonomyLevel.FULL,
                "COORDINATOR",
                () -> tool.execute(ctx("session-456", "user-123"), params));

        assertEquals(400, resp.exitCode());
        assertTrue(resp.output().startsWith("Invalid MCP registration: "), resp.output());
        verifyNoInteractions(managementService);
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
