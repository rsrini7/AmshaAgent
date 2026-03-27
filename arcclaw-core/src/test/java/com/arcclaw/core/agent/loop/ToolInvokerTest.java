package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolInvokerTest {

    private ToolInvoker invoker;
    private ToolRegistry toolRegistry;
    private ToolObservationSanitizer observationSanitizer;
    private AgentOtelContext agentOtelContext;

    @BeforeEach
    void setUp() {
        invoker = new ToolInvoker();
        toolRegistry = mock(ToolRegistry.class);
        observationSanitizer = mock(ToolObservationSanitizer.class);
        agentOtelContext = mock(AgentOtelContext.class);

        invoker.toolRegistry = toolRegistry;
        invoker.observationSanitizer = observationSanitizer;
        invoker.agentOtelContext = agentOtelContext;

        when(observationSanitizer.sanitize(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));

        // Mock OTel to return invalid (no-op) spans by default
        Span invalidSpan = Span.getInvalid();
        when(agentOtelContext.startToolSpan(anyString(), anyString(), anyString())).thenReturn(invalidSpan);
    }

    @Test
    void testInvokeTool_success() {
        when(toolRegistry.invokeTool(anyString(), anyMap())).thenReturn(new ToolResponse(0, "success", true, false));

        ToolResponse response = invoker.invoke("tool", Map.of(), "s1");

        assertTrue(response.success());
        assertEquals("success", response.output());
    }

    @Test
    void testInvokeTool_securityException() {
        when(toolRegistry.invokeTool(anyString(), anyMap())).thenThrow(new SecurityException("Blocked"));

        ToolResponse response = invoker.invoke("tool", Map.of(), "s1");

        assertFalse(response.success());
        assertTrue(response.output().contains("Security violation"));
        assertTrue(response.output().contains("Blocked"));
    }

    @Test
    void testInvokeTool_genericException() {
        when(toolRegistry.invokeTool(anyString(), anyMap())).thenThrow(new RuntimeException("Crash"));

        ToolResponse response = invoker.invoke("tool", Map.of(), "s1");

        assertFalse(response.success());
        assertTrue(response.output().contains("Tool execution error"));
        assertTrue(response.output().contains("Crash"));
    }

    @Test
    void testInvokeTool_requiresApprovalExceptionBubblesUp() {
        RequiresApprovalException ex = new RequiresApprovalException("tool", "{}", "s1");
        when(toolRegistry.invokeTool(anyString(), anyMap())).thenThrow(ex);

        assertThrows(RequiresApprovalException.class, () -> invoker.invoke("tool", Map.of(), "s1"));
    }
}
