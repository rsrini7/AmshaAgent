package com.amsha.core.observability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.opentelemetry.api.trace.Span;

@ExtendWith(MockitoExtension.class)
class AgentTraceEnricherTest {

    @Mock
    AgentOtelContext otelContext;

    @Mock
    Span span;

    AgentTraceEnricher enricher;

    @BeforeEach
    void setUp() throws Exception {
        enricher = new AgentTraceEnricher();
        var field = AgentTraceEnricher.class.getDeclaredField("otelContext");
        field.setAccessible(true);
        field.set(enricher, otelContext);
    }

    @Test
    void recordLlmCallDoesNothing() {
        enricher.recordLlmCall("s", 10, 20);
    }

    @Test
    void onStepCompleteSetsAttributeWhenRecording() {
        when(otelContext.getRootSpan("s")).thenReturn(span);
        when(span.isRecording()).thenReturn(true);
        ReasoningStep step = ReasoningStep.pending("thought", "FINISH", Map.of("answer", "done"));

        enricher.onStepComplete("s", "role", step);
        verify(span).setAttribute("amsha.last.step.success", true);
    }

    @Test
    void onStepCompleteSkipsWhenNotRecording() {
        when(otelContext.getRootSpan("s")).thenReturn(span);
        when(span.isRecording()).thenReturn(false);

        enricher.onStepComplete("s", "role", null);
        verify(span, never()).setAttribute(anyString(), any());
    }

    @Test
    void onStepCompleteSkipsWhenRootNull() {
        when(otelContext.getRootSpan("s")).thenReturn(null);
        enricher.onStepComplete("s", "role", null);
    }

    @Test
    void onLoopCompleteSetsAttributesWhenRecording() {
        when(otelContext.getRootSpan("s")).thenReturn(span);
        when(span.isRecording()).thenReturn(true);
        AgentResult result = new AgentResult.Finished("ok", List.of(), 50, 100);

        enricher.onLoopComplete("s", "u", "role", "hash", result);
        verify(span).setAttribute("amsha.loop.complete", true);
        verify(span).setAttribute("amsha.loop.outcome", "Finished");
    }

    @Test
    void onLoopCompleteSkipsWhenNotRecording() {
        when(otelContext.getRootSpan("s")).thenReturn(span);
        when(span.isRecording()).thenReturn(false);

        enricher.onLoopComplete("s", "u", "role", "hash", null);
        verify(span, never()).setAttribute(anyString(), any());
    }

    @Test
    void onLoopCompleteSkipsWhenRootNull() {
        when(otelContext.getRootSpan("s")).thenReturn(null);
        enricher.onLoopComplete("s", "u", "role", "hash", null);
    }
}
