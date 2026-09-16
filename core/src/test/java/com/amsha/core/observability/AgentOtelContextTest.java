package com.amsha.core.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.amsha.tool.api.ToolCapability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

@ExtendWith(MockitoExtension.class)
class AgentOtelContextTest {

    @InjectMocks
    AgentOtelContext agentOtelContext;

    @Mock
    Tracer tracer;

    SpanBuilder spanBuilder;
    Span span;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        spanBuilder = mock(SpanBuilder.class);
        span = mock(Span.class);

        lenient().when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        lenient().when(spanBuilder.setAttribute(anyString(), anyString())).thenReturn(spanBuilder);
        lenient().when(spanBuilder.setAttribute(anyString(), anyLong())).thenReturn(spanBuilder);
        lenient().when(spanBuilder.setAttribute(any(AttributeKey.class), any())).thenReturn(spanBuilder);
        lenient().when(spanBuilder.setParent(any())).thenReturn(spanBuilder);
        lenient().when(spanBuilder.startSpan()).thenReturn(span);
    }

    @Test
    void testSessionSpanCreationAndTokenRecording() {
        String sessionId = "test-session-otel-999";

        // Start session span
        Span rootSpan = agentOtelContext.startSessionSpan(sessionId);
        assertNotNull(rootSpan);

        verify(tracer).spanBuilder(AgentOTelConstants.SPAN_SESSION);
        verify(spanBuilder).setAttribute(AgentOTelConstants.ATTR_SESSION_ID, sessionId);
        verify(spanBuilder).startSpan();

        // Record pseudo LLM call
        agentOtelContext.recordLlmCall(sessionId, 150, 50);

        verify(span).setAttribute("llm.usage.prompt_tokens", 150L);
        verify(span).setAttribute("llm.usage.completion_tokens", 50L);

        // End session span
        agentOtelContext.endSessionSpan(sessionId);

        verify(span).end();
    }

    @Test
    void sessionSpanCanEndOnDifferentThreadWithoutRetainedScope() throws Exception {
        String sessionId = "cross-thread-otel-session";
        agentOtelContext.startSessionSpan(sessionId);

        Thread endThread = Thread.startVirtualThread(() -> agentOtelContext.endSessionSpan(sessionId));
        endThread.join();

        verify(span).end();
        verify(span, never()).makeCurrent();
    }

    @Test
    void testTokenAccumulationAcrossMultipleCalls() {
        String sessionId = "accumulate-otel-session";
        agentOtelContext.startSessionSpan(sessionId);

        // Call 1
        agentOtelContext.recordLlmCall(sessionId, 100, 50);
        verify(span).setAttribute("llm.usage.prompt_tokens", 100L);
        verify(span).setAttribute("llm.usage.completion_tokens", 50L);

        // Call 2
        agentOtelContext.recordLlmCall(sessionId, 200, 75);
        verify(span).setAttribute("llm.usage.prompt_tokens", 300L);
        verify(span).setAttribute("llm.usage.completion_tokens", 125L);

        // Call 3
        agentOtelContext.recordLlmCall(sessionId, 150, 25);
        verify(span).setAttribute("llm.usage.prompt_tokens", 450L);
        verify(span).setAttribute("llm.usage.completion_tokens", 150L);
    }

    @Test
    void testChildSpanParentedToRootSpan() {
        String sessionId = "parent-child-otel-session";
        agentOtelContext.startSessionSpan(sessionId);

        // Act
        agentOtelContext.childSpan(sessionId, "test.operation");

        // Assert: verify setParent was called on the spanBuilder.
        // Note: Context.current().with(mockSpan) returns null in tests because Mockito mocks
        // don't implement OTel's ImplicitContextKeyed. We verify setParent was called (any arg).
        verify(spanBuilder).setParent((Context) any());
        verify(tracer).spanBuilder("test.operation");
    }

    @Test
    void testStartIterationSpan() {
        String sessionId = "iter-session";
        agentOtelContext.startSessionSpan(sessionId);

        Span iterSpan = agentOtelContext.startIterationSpan(sessionId, 1, "coder");
        assertNotNull(iterSpan);

        verify(tracer).spanBuilder(AgentOTelConstants.SPAN_AGENT_ITERATION);
        verify(spanBuilder, times(2)).setAttribute(AgentOTelConstants.ATTR_SESSION_ID, sessionId);
        verify(spanBuilder).setAttribute(AgentOTelConstants.ATTR_AGENT_ROLE, "coder");
        verify(spanBuilder).setAttribute(AgentOTelConstants.ATTR_ITERATION, 1L);
        verify(spanBuilder).setParent((Context) any());
    }

    @Test
    void testStartIterationSpanNoRoot() {
        Span iterSpan = agentOtelContext.startIterationSpan("no-root", 1, "coder");
        assertEquals(Span.getInvalid(), iterSpan);
    }

    @Test
    void testStartToolSpan() {
        String sessionId = "tool-session";
        agentOtelContext.startSessionSpan(sessionId);

        Span toolSpan = agentOtelContext.startToolSpan(sessionId, ToolCapability.GITHUB.toolName(), "invoke");
        assertNotNull(toolSpan);

        verify(tracer).spanBuilder(AgentOTelConstants.SPAN_TOOL_CALL);
        verify(spanBuilder, times(2)).setAttribute(AgentOTelConstants.ATTR_SESSION_ID, sessionId);
        verify(spanBuilder).setAttribute(AgentOTelConstants.ATTR_TOOL_NAME, ToolCapability.GITHUB.toolName());
        verify(spanBuilder).setAttribute(AgentOTelConstants.ATTR_TOOL_OPERATION, "invoke");
        verify(spanBuilder).setParent((Context) any());
    }

    @Test
    void testSpanEndSafety() {
        String sessionId = "end-safety";
        agentOtelContext.startSessionSpan(sessionId);
        Span span = agentOtelContext.startIterationSpan(sessionId, 1, "coder");

        // Calling end multiple times or on invalid span should be safe
        span.end();
        span.end();

        Span invalid = Span.getInvalid();
        invalid.end();
    }
}
