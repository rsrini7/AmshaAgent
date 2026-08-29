package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.amsha.core.audit.AuditService;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolResponse;
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

    @Test
    void testInvokeToolGovernanceFailures() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("guarded_tool");
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.EXECUTE_SHELL);
        when(discoveryService.getToolIndex()).thenReturn(Map.of("guarded_tool", mockTool));

        doThrow(new SecurityException("Role forbidden"))
                .when(governanceGuard)
                .verifyRoleCapability(any(), eq("guarded_tool"));

        ToolResponse resp1 = registry.invokeTool("guarded_tool", Map.of());
        assertFalse(resp1.success());
        assertTrue(resp1.output().contains("Role forbidden"));

        reset(governanceGuard);
        doThrow(new IllegalArgumentException("Autonomy policy denied"))
                .when(governanceGuard)
                .verifyAutonomy(any(), eq("guarded_tool"), any(), any());

        ToolResponse resp2 = registry.invokeTool("guarded_tool", Map.of());
        assertFalse(resp2.success());
        assertTrue(resp2.output().contains("Autonomy policy denied"));
    }

    @Test
    void testInvokeToolWithNullStrippingAndSchemaFiltering() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("schema_tool");
        when(mockTool.requiresSandbox()).thenReturn(false);
        when(mockTool.shouldStripNullParams()).thenReturn(true);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);

        JsonObjectSchema schema =
                JsonObjectSchema.builder().addStringProperty("allowed").build();
        when(mockTool.parametersSchema()).thenReturn(schema);

        when(mockTool.execute(any(), any())).thenAnswer(inv -> {
            Map<String, Object> map = inv.getArgument(1);
            return ToolResponse.success("keys=" + map.keySet());
        });

        when(discoveryService.getToolIndex()).thenReturn(Map.of("schema_tool", mockTool));

        Map<String, Object> input = new HashMap<>();
        input.put("allowed", "val");
        input.put("hallucinated", "extra");
        input.put("null_key", null);

        ToolResponse resp = registry.invokeTool("schema_tool", input);
        assertTrue(resp.success());
        assertEquals("keys=[allowed]", resp.output());
    }

    @Test
    void testInvokeDirectExecutionException() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("failing_tool");
        when(mockTool.requiresSandbox()).thenReturn(false);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);
        when(mockTool.execute(any(), any())).thenThrow(new RuntimeException("Execution crash"));

        when(discoveryService.getToolIndex()).thenReturn(Map.of("failing_tool", mockTool));

        ToolResponse resp = registry.invokeTool("failing_tool", Map.of());
        assertNotNull(resp);
        assertFalse(resp.success());
    }
}
