package com.amsha.mcp.reflex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.reflex.ReflexResult;
import com.amsha.mcp.bridge.McpToolBridge;
import com.amsha.mcp.bridge.McpToolDescriptor;
import com.amsha.mcp.tool.DynamicToolCapability;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.mcp.transport.McpApprovalService;
import com.amsha.mcp.transport.McpManagementService;
import com.amsha.mcp.transport.McpSetupTokenService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpReflexTest {

    @Mock
    DynamicToolCapabilityRegistry registry;

    @Mock
    McpToolBridge mcpToolBridge;

    @Mock
    McpManagementService managementService;

    @Mock
    McpSetupTokenService setupTokenService;

    @InjectMocks
    McpReflex reflex;

    @BeforeEach
    void keepStatusTestsOnRegistrySurface() {
        reflex.managementService = null;
    }

    private MessageContext context() {
        ChannelUser user = new ChannelUser("user1", "chat1", "User 1", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/mcp", "sess-1", Instant.now());
        return new MessageContext("chat1", user, msg);
    }

    @Test
    void matchesRecognizesSlashMcp() {
        assertTrue(reflex.matches("/mcp"));
        assertTrue(reflex.matches("  /MCP  "));
        assertTrue(reflex.matches("/Mcp"));
    }

    @Test
    void rejectsNonMcpInput() {
        assertFalse(reflex.matches("/status"));
        assertFalse(reflex.matches("mcp"));
        assertFalse(reflex.matches(null));
    }

    @Test
    void discoverCreatesApprovalTaskInsteadOfCallingRemoteDirectly() {
        reflex.managementService = managementService;
        var pending = new McpApprovalService.PendingApproval(
                "task-1",
                "DISCOVER",
                "server",
                "https://example.test/mcp",
                null,
                Instant.now().plusSeconds(60));
        when(managementService.requestDiscovery("user1", "server", "https://example.test/mcp", Map.of()))
                .thenReturn(pending);

        ReflexResult result =
                reflex.execute("sess-1", "user1", "chat1", "/mcp discover server https://example.test/mcp", context());

        assertTrue(result.commandResponse().orElseThrow().contains("task-1"));
        verify(managementService).requestDiscovery("user1", "server", "https://example.test/mcp", Map.of());
    }

    @Test
    void denyRejectsMcpApprovalTask() {
        reflex.managementService = managementService;
        when(managementService.rejectApproval("user1", "task-1")).thenReturn(true);

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/mcp deny task-1", context());

        assertEquals("✅ MCP approval denied.", result.commandResponse().orElseThrow());
        verify(managementService).rejectApproval("user1", "task-1");
    }

    @Test
    void executeShowsNoDynamicToolsWhenRegistryEmpty() {
        McpToolDescriptor exposed = new McpToolDescriptor("serper_web_search", "Web search", "{}");
        when(mcpToolBridge.getExposedTools()).thenReturn(List.of(exposed));
        when(registry.getAll()).thenReturn(Collections.emptyList());

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/mcp", context());

        assertTrue(result.commandResponse().isPresent());
        String output = result.commandResponse().get();
        assertTrue(output.contains("MCP Status"));
        assertTrue(output.contains("serper_web_search"));
        assertTrue(output.contains("none connected"));
    }

    @Test
    void executeShowsBothExposedAndDynamicTools() {
        McpToolDescriptor exposed = new McpToolDescriptor("sandbox_exec", "Execute code", "{}");
        when(mcpToolBridge.getExposedTools()).thenReturn(List.of(exposed));

        DynamicToolCapability dynamic = new DynamicToolCapability(
                "custom_tool", "Custom tool", "my-server", McpTransportType.HTTP_SSE, false, "{}");
        when(registry.getAll()).thenReturn(List.of(dynamic));

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/mcp", context());

        String output = result.commandResponse().get();
        assertTrue(output.contains("sandbox_exec"));
        assertTrue(output.contains("custom_tool"));
        assertTrue(output.contains("my-server"));
        assertTrue(output.contains("HTTP_SSE"));
        assertFalse(output.contains("none connected"));
    }

    @Test
    void executeShowsMultipleExposedTools() {
        McpToolDescriptor tool1 = new McpToolDescriptor("tool_a", "Tool A", "{}");
        McpToolDescriptor tool2 = new McpToolDescriptor("tool_b", "Tool B", "{}");
        when(mcpToolBridge.getExposedTools()).thenReturn(List.of(tool1, tool2));
        when(registry.getAll()).thenReturn(Collections.emptyList());

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/mcp", context());

        String output = result.commandResponse().get();
        assertTrue(output.contains("tool_a"));
        assertTrue(output.contains("tool_b"));
        assertTrue(output.contains("2"));
    }
}
