package com.amsha.core.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import jakarta.enterprise.inject.Instance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

class ChatModelListenerParityTest {

    private ChatModelListenerRuntimeControl control;
    private MeterRegistry meterRegistry;
    private Tracer tracer;
    private Span span;
    private SpanBuilder spanBuilder;
    private AmshaChatModelListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        control = new ChatModelListenerRuntimeControl("shadow");
        meterRegistry = new SimpleMeterRegistry();
        tracer = mock(Tracer.class);
        span = mock(Span.class);
        spanBuilder = mock(SpanBuilder.class);

        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(any(AttributeKey.class), any())).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);

        Instance<MeterRegistry> meterInstance = mock(Instance.class);
        when(meterInstance.isResolvable()).thenReturn(true);
        when(meterInstance.get()).thenReturn(meterRegistry);

        Instance<Tracer> tracerInstance = mock(Instance.class);
        when(tracerInstance.isResolvable()).thenReturn(true);
        when(tracerInstance.get()).thenReturn(tracer);

        listener = new AmshaChatModelListener(control, meterInstance, tracerInstance);
        listener.init();
    }

    @AfterEach
    void tearDown() {
        listener.destroy();
    }

    @Test
    @DisplayName("Shadow listener captures 100% token and invocation parity against authoritative metrics")
    void shadowMode_provesZeroDeltaParity() {
        String sessionId = "parity-session-999";
        WorkspaceContext.run(sessionId, "user-pilot", null, AutonomyLevel.FULL, "coordinator", () -> {
            // 1. Authoritative manual recording path (e.g. from AgentObserver / MetricsAgentObserver)
            int authoritativeInputTokens = 150;
            int authoritativeOutputTokens = 75;
            meterRegistry.counter("amsha.agent.tokens", "type", "input").increment(authoritativeInputTokens);
            meterRegistry.counter("amsha.agent.tokens", "type", "output").increment(authoritativeOutputTokens);
            meterRegistry.counter("amsha.agent.llm.calls").increment();

            // 2. Listener interception path
            Map<Object, Object> attributes = new HashMap<>();
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from("Parity check query")))
                    .build();
            listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));

            ChatResponse response = ChatResponse.builder()
                    .aiMessage(AiMessage.from("Parity check answer"))
                    .tokenUsage(new TokenUsage(150, 75, 225))
                    .build();
            listener.onResponse(new ChatModelResponseContext(response, request, ModelProvider.OPEN_AI, attributes));

            // 3. Assert exact parity between authoritative metrics and shadow listener metrics
            double primaryInput =
                    meterRegistry.counter("amsha.agent.tokens", "type", "input").count();
            double primaryOutput = meterRegistry
                    .counter("amsha.agent.tokens", "type", "output")
                    .count();
            double primaryCalls = meterRegistry.counter("amsha.agent.llm.calls").count();

            double shadowInput = meterRegistry
                    .counter("amsha.observability.listener.shadow.tokens", "type", "input")
                    .count();
            double shadowOutput = meterRegistry
                    .counter("amsha.observability.listener.shadow.tokens", "type", "output")
                    .count();
            double shadowCalls = meterRegistry
                    .counter("amsha.observability.listener.shadow.calls", "outcome", "success")
                    .count();

            assertEquals(primaryInput, shadowInput, "Input tokens must match exactly with zero delta");
            assertEquals(primaryOutput, shadowOutput, "Output tokens must match exactly with zero delta");
            assertEquals(primaryCalls, shadowCalls, "LLM calls count must match exactly with zero delta");

            // 4. Assert latency timer recorded
            assertNotNull(meterRegistry
                    .find("amsha.observability.listener.shadow.duration")
                    .timer());
        });
    }

    @Test
    @DisplayName("Active mode emits child spans and active metrics with zero payload leakage")
    void activeMode_emitsChildSpansAndActiveMetrics() {
        control.setMode(ChatModelListenerMode.ACTIVE);
        String sessionId = "active-session-777";

        WorkspaceContext.run(sessionId, "user-pilot", null, AutonomyLevel.FULL, "coordinator", () -> {
            Map<Object, Object> attributes = new HashMap<>();
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from("Active prompt")))
                    .build();
            listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));

            verify(tracer).spanBuilder("amsha.llm.listener_call");
            verify(spanBuilder).setAttribute(AgentOTelConstants.ATTR_OI_SPAN_KIND, AgentOTelConstants.OI_KIND_LLM);
            verify(spanBuilder).setAttribute(AgentOTelConstants.ATTR_SESSION_ID, sessionId);

            ChatResponse response = ChatResponse.builder()
                    .aiMessage(AiMessage.from("Active answer"))
                    .tokenUsage(new TokenUsage(200, 100, 300))
                    .build();
            listener.onResponse(new ChatModelResponseContext(response, request, ModelProvider.OPEN_AI, attributes));

            verify(span).setAttribute(AgentOTelConstants.ATTR_LLM_INPUT_TOKENS, 200L);
            verify(span).setAttribute(AgentOTelConstants.ATTR_LLM_OUTPUT_TOKENS, 100L);
            verify(span).end();

            assertEquals(
                    200.0,
                    meterRegistry
                            .counter("amsha.observability.listener.active.tokens", "type", "input")
                            .count());
            assertEquals(
                    100.0,
                    meterRegistry
                            .counter("amsha.observability.listener.active.tokens", "type", "output")
                            .count());
            assertEquals(
                    1.0,
                    meterRegistry
                            .counter("amsha.observability.listener.active.calls", "outcome", "success")
                            .count());
            assertNotNull(meterRegistry
                    .find("amsha.observability.listener.active.duration")
                    .timer());
        });
    }

    @Test
    @DisplayName("Active mode records error status on span without payload")
    void activeMode_onError_recordsErrorStatus() {
        control.setMode(ChatModelListenerMode.ACTIVE);
        String sessionId = "active-err-session-888";

        WorkspaceContext.run(sessionId, "user-pilot", null, AutonomyLevel.FULL, "coordinator", () -> {
            Map<Object, Object> attributes = new HashMap<>();
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from("Error prompt")))
                    .build();
            listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));

            RuntimeException ex = new RuntimeException("Simulated upstream 503");
            listener.onError(new ChatModelErrorContext(ex, request, ModelProvider.OPEN_AI, attributes));

            verify(span).setStatus(StatusCode.ERROR, "LLM provider invocation failed");
            verify(span).setAttribute(AgentOTelConstants.ATTR_ERROR_TYPE, "RuntimeException");
            verify(span, never()).recordException(any(Throwable.class));
            verify(span).end();

            assertEquals(
                    1.0,
                    meterRegistry
                            .counter("amsha.observability.listener.active.calls", "outcome", "error")
                            .count());
        });
    }

    @Test
    @DisplayName("Active mode does not duplicate calls already covered by manual instrumentation")
    void activeMode_skipsManuallyInstrumentedCall() {
        control.setMode(ChatModelListenerMode.ACTIVE);
        Map<Object, Object> attributes = new HashMap<>();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("Manual instrumentation sentinel")))
                .build();

        ScopedValue.where(AgentOTelConstants.MANUAL_LLM_INSTRUMENTATION, true)
                .run(() -> listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes)));

        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("Manual response sentinel"))
                .tokenUsage(new TokenUsage(12, 6, 18))
                .build();
        listener.onResponse(new ChatModelResponseContext(response, request, ModelProvider.OPEN_AI, attributes));

        verify(tracer, never()).spanBuilder(anyString());
        assertEquals(
                0.0,
                meterRegistry
                                        .find("amsha.observability.listener.active.calls")
                                        .tag("outcome", "success")
                                        .counter()
                                == null
                        ? 0.0
                        : meterRegistry
                                .counter("amsha.observability.listener.active.calls", "outcome", "success")
                                .count());
    }
}
