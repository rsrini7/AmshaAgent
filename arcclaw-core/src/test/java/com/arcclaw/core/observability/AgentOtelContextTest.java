package com.arcclaw.core.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@QuarkusTest
class AgentOtelContextTest {

    @Inject
    AgentOtelContext agentOtelContext;

    @InjectMock
    Tracer tracer;

    SpanBuilder spanBuilder;
    Span span;
    Scope scope;

    @BeforeEach
    void setUp() {
        spanBuilder = mock(SpanBuilder.class);
        span = mock(Span.class);
        scope = mock(Scope.class);

        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setParent(any())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        when(span.makeCurrent()).thenReturn(scope);
    }

    @Test
    void testSessionSpanCreationAndTokenRecording() {
        String sessionId = "test-session-otel-999";

        // Start session span
        Span rootSpan = agentOtelContext.startSessionSpan(sessionId);
        assertNotNull(rootSpan);

        verify(tracer).spanBuilder("arcclaw.session");
        verify(spanBuilder).setAttribute("sessionId", sessionId);
        verify(spanBuilder).startSpan();
        verify(span).makeCurrent();

        // Record pseudo LLM call
        agentOtelContext.recordLlmCall(sessionId, 150, 50);

        verify(span).setAttribute("llm.usage.prompt_tokens", 150L);
        verify(span).setAttribute("llm.usage.completion_tokens", 50L);

        // End session span
        agentOtelContext.endSessionSpan(sessionId);

        verify(span).end();
        verify(scope).close();
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
        verify(spanBuilder).setParent((io.opentelemetry.context.Context) any());
        verify(tracer).spanBuilder("test.operation");
    }
}
