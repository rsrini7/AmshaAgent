package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.enterprise.event.Event;

import java.util.Map;

import com.amsha.core.events.ToolExecutionEvent;
import com.amsha.core.governance.RequiresApprovalException;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.tool.attempt.ToolOperationIdentity;
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
        when(toolRegistry.invokeToolWithOperationId(anyString(), anyMap(), anyString()))
                .thenReturn(ToolResponse.success("success"));

        ToolResponse response = invokeWithIdentity("tool", Map.of(), "s1");

        assertTrue(response.success());
        assertEquals("success", response.output());
    }

    @Test
    void invokeWithOperationIdForwardsIdentityToRegistry() {
        String operationId = "123e4567-e89b-12d3-a456-426614174099";
        when(toolRegistry.invokeToolWithOperationId(anyString(), anyMap(), eq(operationId)))
                .thenReturn(ToolResponse.success("success"));

        ToolResponse response = invoker.invoke("file_read", Map.of(), "s-operation", operationId);

        assertEquals("success", response.output());
        verify(toolRegistry).invokeToolWithOperationId("file_read", Map.of(), operationId);
    }

    @Test
    void testInvokeTool_securityException() {
        when(toolRegistry.invokeToolWithOperationId(anyString(), anyMap(), anyString()))
                .thenThrow(new SecurityException("Blocked"));

        ToolResponse response = invokeWithIdentity("tool", Map.of(), "s1");

        assertFalse(response.success());
        assertTrue(response.output().contains("Security violation"));
        assertTrue(response.output().contains("Blocked"));
    }

    @Test
    void testInvokeTool_genericException() {
        when(toolRegistry.invokeToolWithOperationId(anyString(), anyMap(), anyString()))
                .thenThrow(new RuntimeException("Crash"));

        ToolResponse response = invokeWithIdentity("tool", Map.of(), "s1");

        assertFalse(response.success());
        assertTrue(response.output().contains("Tool execution error"));
        assertTrue(response.output().contains("Crash"));
    }

    @Test
    void testInvokeTool_requiresApprovalExceptionBubblesUp() {
        RequiresApprovalException ex = new RequiresApprovalException("tool", "{}", "s1");
        when(toolRegistry.invokeToolWithOperationId(anyString(), anyMap(), anyString()))
                .thenThrow(ex);

        assertThrows(RequiresApprovalException.class, () -> invokeWithIdentity("tool", Map.of(), "s1"));
    }

    @Test
    void testInvokeTool_haltedSessionAbortsToolInvocation() {
        String sessionId = "s-halted-" + System.currentTimeMillis();
        WorkspaceManager.haltSession(sessionId);
        try {
            ToolResponse response = invokeWithIdentity("any_tool", Map.of(), sessionId);
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
            ToolResponse response = invokeWithIdentity("any_tool", Map.of(), "s-normal");
            assertFalse(response.success());
            assertEquals(499, response.exitCode());
            assertTrue(response.output().contains("Execution aborted"));
        } finally {
            Thread.interrupted(); // clear interrupted status
        }
    }

    @Test
    void legacyInvocationWithoutOperationIdFailsClosed() {
        ToolResponse response = invoker.invoke("tool", Map.of(), "s1");

        assertFalse(response.success());
        assertEquals(409, response.exitCode());
        verifyNoInteractions(toolRegistry);
    }

    private ToolResponse invokeWithIdentity(String toolName, Map<String, Object> params, String sessionId) {
        return invoker.invoke(toolName, params, sessionId, ToolOperationIdentity.generateCanonicalUuid());
    }
}
