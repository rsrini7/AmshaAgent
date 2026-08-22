package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.Collections;
import java.util.Map;

import com.amsha.core.audit.AuditService;
import com.amsha.security.api.ToolOperation;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolRegistryUnitTest {

    private ToolRegistry registry;
    private ToolDiscoveryService discoveryService;
    private ToolGovernanceGuard governanceGuard;
    private ToolSecretExtractor secretExtractor;
    private AuditService auditService;
    private Event<ToolDiscoveryCompleted> toolDiscoveryCompleted;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        registry = new ToolRegistry();
        discoveryService = mock(ToolDiscoveryService.class);
        governanceGuard = mock(ToolGovernanceGuard.class);
        secretExtractor = mock(ToolSecretExtractor.class);
        auditService = mock(AuditService.class);
        toolDiscoveryCompleted = mock(Event.class);

        registry.discoveryService = discoveryService;
        registry.governanceGuard = governanceGuard;
        registry.secretExtractor = secretExtractor;
        registry.auditService = auditService;
        registry.toolDiscoveryCompleted = toolDiscoveryCompleted;
        registry.objectMapper = new ObjectMapper();
        registry.workspaceRootDir = "./data/workspaces";
    }

    @Test
    void testToSafeDirName() {
        assertEquals("session_123", ToolRegistry.toSafeDirName("session:123"));
        assertEquals("safe-session", ToolRegistry.toSafeDirName("safe-session"));
    }

    @Test
    void testListToolsAndFindTool() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("my_tool");
        when(discoveryService.getToolIndex()).thenReturn(Map.of("my_tool", mockTool));

        assertEquals(1, registry.listTools().size());
        assertEquals("my_tool", registry.listTools().get(0));
        assertEquals("my_tool", registry.registeredToolNames());
        assertTrue(registry.findTool("my_tool").isPresent());
        assertFalse(registry.findTool("unknown").isPresent());
    }

    @Test
    void testHeadroomToolCallIntercepted() {
        ToolResponse resp1 = registry.invokeTool("headroom_search", Map.of());
        assertNotNull(resp1);
        assertTrue(resp1.success());

        ToolResponse resp2 = registry.invokeTool("headroom", Map.of());
        assertNotNull(resp2);
        assertTrue(resp2.success());
    }

    @Test
    void testInvokeUnknownToolThrows() {
        when(discoveryService.getToolIndex()).thenReturn(Map.of());
        assertThrows(IllegalArgumentException.class, () -> registry.invokeTool("unknown_tool", Map.of()));
    }

    @Test
    void testInvokeKnownToolSuccess() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("simple_tool");
        when(mockTool.requiresSandbox()).thenReturn(false);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);
        when(mockTool.execute(any(), any())).thenReturn(ToolResponse.success("ok"));

        when(discoveryService.getToolIndex()).thenReturn(Map.of("simple_tool", mockTool));

        ToolResponse resp = registry.invokeTool("simple_tool", Map.of("p", "v"));
        assertNotNull(resp);
        assertTrue(resp.success());
        assertEquals("ok", resp.output());
    }

    @Test
    void testInitializeFiresEvent() {
        when(discoveryService.getToolIndex()).thenReturn(Map.of("t1", mock(AgentTool.class)));
        registry.initialize();
        verify(discoveryService).discover();
        verify(toolDiscoveryCompleted).fire(any());
    }

    @Test
    void testInvokeSandboxedSanitizedInfraFailure() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("sandboxed_tool");
        when(mockTool.requiresSandbox()).thenReturn(true);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.EXECUTE_SHELL);

        when(discoveryService.getToolIndex()).thenReturn(Map.of("sandboxed_tool", mockTool));
        when(secretExtractor.extractRequiredSecrets(any(), any())).thenReturn(Map.of());

        // Without sandbox port
        registry.beanManager = mock(BeanManager.class);
        when(registry.beanManager.getBeans(Object.class)).thenReturn(Collections.emptySet());

        ToolResponse resp = registry.invokeTool("sandboxed_tool", Map.of());
        assertNotNull(resp);
        assertFalse(resp.success());
        assertEquals("Sandbox execution environment is currently unavailable.", resp.output());
    }
}
