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

        verify(span).setAttribute("llm.usage.prompt_tokens", 150);
        verify(span).setAttribute("llm.usage.completion_tokens", 50);

        // End session span
        agentOtelContext.endSessionSpan(sessionId);

        verify(span).end();
        verify(scope).close();
    }
}
