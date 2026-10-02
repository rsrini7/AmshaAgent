package com.amsha.mcp.reflex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.mcp.bridge.McpToolBridge;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.transport.McpApprovalService;
import com.amsha.mcp.transport.McpConnectionException;
import com.amsha.mcp.transport.McpManagementService;
import com.amsha.mcp.transport.McpSetupLinkService;
import com.amsha.mcp.transport.McpSetupTokenService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the {@code /mcp} command surface that {@link McpReflexTest} leaves dark: the remaining {@code switch}
 * arms, both argument guards, the exception-to-chat-text path, and the status renderer's managed-server branch.
 *
 * <p>Unlike {@code McpReflexTest}, which deliberately nulls {@code managementService} in its {@code @BeforeEach}
 * to keep its status assertions on the registry surface, this class leaves the injected mock in place — the
 * managed-server commands are exactly what is under test here.
 */
@ExtendWith(MockitoExtension.class)
class McpReflexCommandSurfaceTest {

    @Mock
    DynamicToolCapabilityRegistry registry;

    @Mock
    McpToolBridge mcpToolBridge;

    @Mock
    McpManagementService managementService;

    @Mock
    McpSetupTokenService setupTokenService;

    @Mock
    McpSetupLinkService setupLinkService;

    @InjectMocks
    McpReflex reflex;

    private MessageContext context() {
        ChannelUser user = new ChannelUser("user1", "chat1", "User 1", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/mcp", "sess-1", Instant.now());
        return new MessageContext("chat1", user, msg);
    }

    private String run(String input) {
        return reflex.execute("sess-1", "user1", "chat1", input, context())
                .commandResponse()
                .orElseThrow();
    }

    private static McpManagementService.ConnectionSnapshot snapshot(String name) {
        return new McpManagementService.ConnectionSnapshot(
                name, "https://example.test/mcp", "CONNECTED", "HTTP_SSE", 2L, 5L, 7L, List.of());
    }

    private static McpApprovalService.PendingApproval pending(String operation) {
        return new McpApprovalService.PendingApproval(
                "task-9",
                operation,
                "server",
                "https://example.test/mcp",
                null,
                Instant.now().plusSeconds(60));
    }

    // ---- status surface -------------------------------------------------------

    @Test
    void matchesAcceptsSlashMcpWithArgumentsButNotALongerWord() {
        assertTrue(reflex.matches("/mcp status"), "the predicate must route commands that carry arguments");
        assertTrue(reflex.matches("  /MCP setup server  "));
        assertFalse(reflex.matches("/mcpx"), "a longer word must not be mistaken for the command prefix");
    }

    @Test
    void listAndStatusPrintTheStatusWithoutTheHelpGuide() {
        when(mcpToolBridge.getExposedTools()).thenReturn(List.of());
        when(managementService.list("user1")).thenReturn(List.of());

        String listed = run("/mcp list");
        String status = run("/mcp status");

        assertTrue(listed.contains("MCP Status"), "list must print the status block");
        assertFalse(listed.contains("/mcp setup <name>"), "list must not print the help guide");
        assertTrue(status.contains("MCP Status"));
        assertTrue(status.contains("*Managed servers* (0):"));
        assertTrue(status.contains("• none"));
    }

    @Test
    void statusRendersManagedServersWithEnabledOverDiscoveredToolCounts() {
        when(mcpToolBridge.getExposedTools()).thenReturn(List.of());
        when(managementService.list("user1")).thenReturn(List.of(snapshot("alpha"), snapshot("beta")));

        String output = run("/mcp status");

        assertTrue(output.contains("*Managed servers* (2):"));
        assertTrue(output.contains("`alpha` — CONNECTED (2/5 tools)"));
        assertTrue(output.contains("`beta` — CONNECTED (2/5 tools)"));
    }

    // ---- lifecycle commands --------------------------------------------------

    @Test
    void connectWithoutAnEndpointReconnectsInsteadOfOpeningANewConnection() {
        when(managementService.requestReconnect("user1", "server")).thenReturn(pending("RECONNECT"));

        assertTrue(run("/mcp connect server").contains("task-9"));

        verify(managementService).requestReconnect("user1", "server");
        verify(managementService, never()).requestConnect(any(), any(), any(), any());
    }

    @Test
    void connectWithAnEndpointRequestsAFreshConnection() {
        when(managementService.requestConnect("user1", "server", "https://example.test/mcp", Map.of()))
                .thenReturn(pending("CONNECT"));

        assertTrue(run("/mcp connect server https://example.test/mcp").contains("task-9"));

        verify(managementService).requestConnect("user1", "server", "https://example.test/mcp", Map.of());
        verify(managementService, never()).requestReconnect(any(), any());
    }

    @Test
    void activateReconnectAndResumeAllRouteThroughTheReconnectApproval() {
        when(managementService.requestReconnect("user1", "server")).thenReturn(pending("RECONNECT"));

        assertTrue(run("/mcp activate server").contains("task-9"));
        assertTrue(run("/mcp reconnect server").contains("task-9"));
        assertTrue(run("/mcp resume server").contains("task-9"));

        verify(managementService, times(3)).requestReconnect("user1", "server");
    }

    @Test
    void pauseAndDisableReportTheConnectionSnapshot() {
        when(managementService.pause("user1", "server")).thenReturn(snapshot("server"));
        when(managementService.disableTool("user1", "server", "tool")).thenReturn(snapshot("server"));

        assertEquals("✅ Paused MCP server `server` (2/5 tools enabled).", run("/mcp pause server"));
        assertEquals("✅ Disabled MCP server `server` (2/5 tools enabled).", run("/mcp disable server tool"));
    }

    @Test
    void enableRequestsAToolActivationApproval() {
        when(managementService.requestEnableTool("user1", "server", "tool")).thenReturn(pending("ENABLE_TOOL"));

        String output = run("/mcp enable server tool");

        assertTrue(output.contains("task-9"));
        assertTrue(output.contains("enable_tool"), "the pending text lowercases the operation name");
    }

    @Test
    void disconnectConfirmsTheRemoval() {
        when(managementService.disconnect("user1", "server")).thenReturn(true);

        assertEquals("✅ Disconnected MCP server `server`.", run("/mcp disconnect server"));
    }

    @Test
    void disconnectOfAMissingConnectionSurfacesAsAChatError() {
        when(managementService.disconnect("user1", "ghost")).thenReturn(false);

        assertEquals("❌ MCP operation failed: connection not found", run("/mcp disconnect ghost"));
    }

    @Test
    void denyOfAnUnknownTaskIsReportedRatherThanClaimedAsDenied() {
        when(managementService.rejectApproval("user1", "ghost")).thenReturn(false);

        assertEquals("⚠️ MCP approval task not found.", run("/mcp deny ghost"));
    }

    // ---- tools ---------------------------------------------------------------

    @Test
    void toolsListsTheDiscoveredNamesOrSaysThereAreNone() {
        when(managementService.listTools("user1", "server"))
                .thenReturn(List.of(
                        new McpManagementService.ToolPreview("read", "Read", "{}", "h1"),
                        new McpManagementService.ToolPreview("write", "Write", "{}", "h2")));
        when(managementService.listTools("user1", "other")).thenReturn(List.of());

        String listed = run("/mcp tools server");

        assertTrue(listed.contains("🧰 MCP tools for `server`:"));
        assertTrue(listed.contains("• read"));
        assertTrue(listed.contains("• write"));

        assertEquals("No discovered tools for MCP server `other`.", run("/mcp tools other"));
    }

    // ---- approvals -----------------------------------------------------------

    @Test
    void approveRendersADiscoveryPreviewAConnectionSnapshotAndAFallback() {
        when(managementService.approve("user1", "t1"))
                .thenReturn(new McpManagementService.ApprovalExecution(
                        "t1",
                        "DISCOVER",
                        new McpManagementService.DiscoveryPreview(
                                "srv",
                                "https://example.test/mcp",
                                "HTTP_SSE",
                                List.of(new McpManagementService.ToolPreview("a", "A", "{}", "h")))));
        when(managementService.approve("user1", "t2"))
                .thenReturn(new McpManagementService.ApprovalExecution("t2", "CONNECT", snapshot("srv")));
        when(managementService.approve("user1", "t3"))
                .thenReturn(new McpManagementService.ApprovalExecution("t3", "ROTATE_HEADERS", "opaque"));

        assertEquals("✅ Discovered `srv`: 1 tools discovered; none enabled.", run("/mcp approve t1"));
        assertEquals("✅ connect MCP server `srv` (2/5 tools enabled).", run("/mcp approve t2"));
        assertEquals("✅ MCP operation completed.", run("/mcp approve t3"));
    }

    // ---- guards and fallbacks ------------------------------------------------

    @Test
    void anUnknownCommandFallsBackToTheUsageLine() {
        assertEquals("Usage: /mcp help", run("/mcp frobnicate"));
    }

    @Test
    void tooFewArgumentsAreReportedAsUsage() {
        assertEquals("❌ MCP operation failed: Usage: /mcp setup <name>", run("/mcp setup"));
        assertEquals("❌ MCP operation failed: Usage: /mcp discover <name> <https-url>", run("/mcp discover only-name"));
    }

    @Test
    void credentialsTypedIntoChatAreRefusedRatherThanForwarded() {
        String refusal = "❌ MCP operation failed: Credentials must be configured in the MCP tab, never in chat";

        assertEquals(refusal, run("/mcp connect server apiKey=secret"));
        assertEquals(refusal, run("/mcp discover server http"));

        verifyNoInteractions(setupTokenService);
    }

    @Test
    void aBlankChannelContextDegradesToChatAndUnknown() {
        MessageContext blank = mock(MessageContext.class);
        when(blank.getChannelType()).thenReturn(null);
        when(blank.getChatId()).thenReturn("   ");
        when(setupTokenService.issue("user1", "chat", "unknown", "server")).thenReturn("tok");
        when(setupLinkService.link("tok")).thenReturn("https://agent.example/#/mcp/setup?token=tok");

        String output = reflex.execute("sess-1", "user1", "chat1", "/mcp setup server", blank)
                .commandResponse()
                .orElseThrow();

        assertTrue(output.contains("https://agent.example/#/mcp/setup?token=tok"));
        verify(setupTokenService).issue("user1", "chat", "unknown", "server");
    }

    /**
     * A null {@link MessageContext} still resolves the channel defaults — the token is issued against
     * {@code chat}/{@code chatId} — and only then does {@code ReflexResult.respond} dereference the context and
     * fail. The test pins both halves so the coupling is visible rather than latent.
     */
    @Test
    void aNullMessageContextResolvesChannelDefaultsBeforeTheResponseFails() {
        when(setupTokenService.issue("user1", "chat", "chat1", "server")).thenReturn("tok");

        assertThrows(
                NullPointerException.class,
                () -> reflex.execute("sess-1", "user1", "chat1", "/mcp setup server", null));

        verify(setupTokenService).issue("user1", "chat", "chat1", "server");
    }

    @Test
    void setupLinkFallsBackToTheFragmentRouteWhenTheLinkServiceIsAbsent() {
        reflex.setupLinkService = null;
        when(setupTokenService.issue("user1", "cli", "chat1", "server")).thenReturn("tok");

        String output = run("/mcp setup server");

        assertTrue(output.contains("/#/mcp/setup?token=tok"), () -> "output was: " + output);
    }

    @Test
    void aNullChatIdAlsoResolvesToUnknown() {
        MessageContext noChatId = mock(MessageContext.class);
        when(noChatId.getChannelType()).thenReturn(ChannelType.CLI);
        when(noChatId.getChatId()).thenReturn(null);
        when(setupTokenService.issue("user1", "cli", "unknown", "server")).thenReturn("tok");
        when(setupLinkService.link("tok")).thenReturn("https://agent.example/#/mcp/setup?token=tok");

        String output = reflex.execute("sess-1", "user1", null, "/mcp setup server", noChatId)
                .commandResponse()
                .orElseThrow();

        assertTrue(output.contains("token=tok"));
        verify(setupTokenService).issue("user1", "cli", "unknown", "server");
    }

    @Test
    void aBlankFailureMessageFallsBackToTheExceptionTypeName() {
        when(managementService.pause("user1", "server")).thenThrow(new McpConnectionException("   "));

        assertEquals("❌ MCP operation failed: McpConnectionException", run("/mcp pause server"));
    }

    @Test
    void aNullFailureMessageAlsoFallsBackToTheExceptionTypeName() {
        when(managementService.pause("user1", "server")).thenThrow(new McpConnectionException((String) null));

        assertEquals("❌ MCP operation failed: McpConnectionException", run("/mcp pause server"));
    }
}
