package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.event.Event;

import java.util.Map;

import com.amsha.core.events.ToolExecutionEvent;
import com.amsha.core.governance.RequiresApprovalException;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;

class ToolInvokerTest {

    private ToolInvoker invoker;
    private ToolRegistry toolRegistry;
    private ToolObservationSanitizer observationSanitizer;
    private AgentOtelContext agentOtelContext;

    @SuppressWarnings("unchecked")
    private final Event<ToolExecutionEvent> toolEvent = mock(Event.class);

    @BeforeEach
    void setUp() {
        invoker = new ToolInvoker();
        toolRegistry = mock(ToolRegistry.class);
        observationSanitizer = mock(ToolObservationSanitizer.class);
        agentOtelContext = mock(AgentOtelContext.class);

        invoker.toolRegistry = toolRegistry;
        invoker.observationSanitizer = observationSanitizer;
        invoker.agentOtelContext = agentOtelContext;
        invoker.registry = new SimpleMeterRegistry();
        invoker.toolEvent = toolEvent;

        when(observationSanitizer.sanitize(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));

        // Mock OTel to return invalid (no-op) spans by default
        Span invalidSpan = Span.getInvalid();
        when(agentOtelContext.startToolSpan(anyString(), anyString(), anyString()))
                .thenReturn(invalidSpan);
    }

    @Test
    void testInvokeTool_success() {
        when(toolRegistry.invokeTool(anyString(), anyMap())).thenReturn(ToolResponse.success("success"));

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

    @Test
    void testInvokeTool_haltedSessionAbortsToolInvocation() {
        String sessionId = "s-halted-" + System.currentTimeMillis();
        WorkspaceManager.haltSession(sessionId);
        try {
            ToolResponse response = invoker.invoke("any_tool", Map.of(), sessionId);
            assertFalse(response.success());
            assertEquals(499, response.exitCode());
            assertTrue(response.output().contains("Execution aborted"));
        } finally {
            WorkspaceManager.clearHalted(sessionId);
        }
    }

    @Test
    void testInvokeTool_interruptedThreadAbortsToolInvocation() {
        Thread.currentThread().interrupt();
        try {
            ToolResponse response = invoker.invoke("any_tool", Map.of(), "s-normal");
            assertFalse(response.success());
            assertEquals(499, response.exitCode());
            assertTrue(response.output().contains("Execution aborted"));
        } finally {
            Thread.interrupted(); // clear interrupted status
        }
    }
}
