package com.amsha.core.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.core.scope.WorkspaceScopeHolder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Tracer;

class AmshaChatModelListenerTest {

    private ChatModelListenerRuntimeControl control;
    private MeterRegistry meterRegistry;
    private Tracer tracer;
    private AmshaChatModelListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        control = new ChatModelListenerRuntimeControl("shadow");
        meterRegistry = new SimpleMeterRegistry();
        tracer = mock(Tracer.class);

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
        WorkspaceScopeHolder.clear();
    }

    @Test
    @DisplayName("Shadow mode captures token counts and metrics with zero payload text")
    void shadowMode_capturesMetadataAndZeroPayload() {
        WorkspaceScopeHolder.run("test-session-123", "user-xyz", null, () -> {
            Map<Object, Object> attributes = new HashMap<>();
            String secretPrompt = "TOP_SECRET_USER_PROMPT_SENTINEL";
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from(secretPrompt)))
                    .build();
            var reqCtx = new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes);

            listener.onRequest(reqCtx);
            assertTrue(attributes.containsKey("amsha.listener.lease"));

            String secretCompletion = "CLASSIFIED_RESPONSE_COMPLETION_SENTINEL";
            ChatResponse response = ChatResponse.builder()
                    .aiMessage(AiMessage.from(secretCompletion))
                    .tokenUsage(new TokenUsage(42, 18, 60))
                    .build();
            var respCtx = new ChatModelResponseContext(response, request, ModelProvider.OPEN_AI, attributes);

            listener.onResponse(respCtx);

            // Verify shadow metrics recorded
            assertEquals(
                    42.0,
                    meterRegistry
                            .counter("amsha.observability.listener.shadow.tokens", "type", "input")
                            .count());
            assertEquals(
                    18.0,
                    meterRegistry
                            .counter("amsha.observability.listener.shadow.tokens", "type", "output")
                            .count());
            assertEquals(
                    1.0,
                    meterRegistry
                            .counter("amsha.observability.listener.shadow.calls", "outcome", "success")
                            .count());
            assertNotNull(meterRegistry
                    .find("amsha.observability.listener.shadow.duration")
                    .timer());
        });
    }

    @Test
    @DisplayName("Null session ID outside workspace does not cause ConcurrentHashMap NPE")
    void nullSessionId_doesNotThrowNpe() {
        // Outside WorkspaceContext binding, no session ID is available.
        Map<Object, Object> concurrentAttributes = new ConcurrentHashMap<>();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("Prompt")))
                .build();
        var reqCtx = new ChatModelRequestContext(request, ModelProvider.OPEN_AI, concurrentAttributes);

        assertDoesNotThrow(() -> listener.onRequest(reqCtx));
        assertTrue(concurrentAttributes.containsKey("amsha.listener.lease"));

        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("Answer"))
                .tokenUsage(new TokenUsage(10, 5, 15))
                .build();
        var respCtx = new ChatModelResponseContext(response, request, ModelProvider.OPEN_AI, concurrentAttributes);
        assertDoesNotThrow(() -> listener.onResponse(respCtx));

        assertEquals(
                10.0,
                meterRegistry
                        .counter("amsha.observability.listener.shadow.tokens", "type", "input")
                        .count());
    }

    @Test
    @DisplayName("Error context in shadow mode records error metric with zero payload")
    void shadowMode_recordsErrorWithoutPayload() {
        WorkspaceScopeHolder.run("err-session-456", "user-xyz", null, () -> {
            Map<Object, Object> attributes = new HashMap<>();
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from("Prompt")))
                    .build();
            var reqCtx = new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes);

            listener.onRequest(reqCtx);

            var errCtx = new ChatModelErrorContext(
                    new RuntimeException("Simulated LLM network timeout"), request, ModelProvider.OPEN_AI, attributes);
            listener.onError(errCtx);

            assertEquals(
                    1.0,
                    meterRegistry
                            .counter("amsha.observability.listener.shadow.calls", "outcome", "error")
                            .count());
        });
    }

    @Test
    @DisplayName("Off mode ignores requests and records zero shadow metrics")
    void offMode_ignoresRequests() {
        control.setMode(ChatModelListenerMode.OFF);

        Map<Object, Object> attributes = new HashMap<>();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("Prompt")))
                .build();
        var reqCtx = new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes);

        listener.onRequest(reqCtx);
        assertFalse(attributes.containsKey("amsha.listener.lease"));

        listener.onResponse(new ChatModelResponseContext(
                ChatResponse.builder().aiMessage(AiMessage.from("dummy")).build(),
                request,
                ModelProvider.OPEN_AI,
                attributes));
        assertEquals(
                0.0,
                meterRegistry
                        .counter("amsha.observability.listener.shadow.tokens", "type", "input")
                        .count());
    }

    @Test
    @DisplayName("Telemetry failure does not escape into the provider call or leak a lease")
    void telemetryFailure_preservesProviderPath() {
        var failingControl = new ChatModelListenerRuntimeControl("shadow");
        MeterRegistry failingRegistry = mock(MeterRegistry.class);
        when(failingRegistry.counter(anyString(), any(String[].class)))
                .thenThrow(new IllegalStateException("telemetry backend unavailable"));

        @SuppressWarnings("unchecked")
        Instance<MeterRegistry> failingMeterInstance = mock(Instance.class);
        when(failingMeterInstance.isResolvable()).thenReturn(true);
        when(failingMeterInstance.get()).thenReturn(failingRegistry);

        @SuppressWarnings("unchecked")
        Instance<Tracer> unavailableTracerInstance = mock(Instance.class);
        when(unavailableTracerInstance.isResolvable()).thenReturn(false);

        var failingListener =
                new AmshaChatModelListener(failingControl, failingMeterInstance, unavailableTracerInstance);
        failingListener.init();
        try {
            Map<Object, Object> attributes = new HashMap<>();
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from("Provider path sentinel")))
                    .build();
            failingListener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));

            ChatResponse response = ChatResponse.builder()
                    .aiMessage(AiMessage.from("Provider response sentinel"))
                    .tokenUsage(new TokenUsage(1, 1, 2))
                    .build();

            assertDoesNotThrow(() -> failingListener.onResponse(
                    new ChatModelResponseContext(response, request, ModelProvider.OPEN_AI, attributes)));
            assertEquals(0, failingControl.snapshot().inFlight());
            assertFalse(attributes.containsKey("amsha.listener.lease"));
        } finally {
            failingListener.destroy();
        }
    }
}
