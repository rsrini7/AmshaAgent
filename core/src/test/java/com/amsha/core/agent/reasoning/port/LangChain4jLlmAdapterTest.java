package com.amsha.core.agent.reasoning.port;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;

import com.amsha.core.routing.ModelRouter;
import com.amsha.llm.api.error.LlmMalformedResponseException;
import com.amsha.llm.api.error.LlmRateLimitException;
import com.amsha.llm.api.error.LlmToolCallException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LangChain4jLlmAdapter} per-model circuit breaker isolation
 * and {@link LangChain4jLlmAdapter.PerModelCircuitBreaker} behavior.
 */
class LangChain4jLlmAdapterTest {

    private ChatModel globalModel;
    private ModelRouter modelRouter;
    private LangChain4jLlmAdapter adapter;

    @BeforeEach
    void setUp() {
        globalModel = mock(ChatModel.class);
        modelRouter = mock(ModelRouter.class);
        adapter = new LangChain4jLlmAdapter(globalModel, modelRouter);
        adapter.cbEnabled = true;
    }

    private static ChatRequest request() {
        return ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build();
    }

    private static ChatResponse response() {
        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
    }

    @Test
    void nextStep_delegatesToGlobalModel_whenNoAgentRole() {
        when(globalModel.chat(any(ChatRequest.class))).thenReturn(response());

        ChatResponse result = adapter.nextStep(request(), null);

        assertNotNull(result);
        verify(globalModel).chat(any(ChatRequest.class));
    }

    @Test
    void nextStep_delegatesToGlobalModel_whenBlankAgentRole() {
        when(globalModel.chat(any(ChatRequest.class))).thenReturn(response());

        ChatResponse result = adapter.nextStep(request(), "  ");

        assertNotNull(result);
        verify(globalModel).chat(any(ChatRequest.class));
    }

    @Test
    void nextStep_routesToResolvedModel_whenAgentRoleProvided() {
        ChatModel personaModel = mock(ChatModel.class);
        when(modelRouter.resolve("coder")).thenReturn(personaModel);
        when(personaModel.chat(any(ChatRequest.class))).thenReturn(response());

        ChatResponse result = adapter.nextStep(request(), "coder");

        assertNotNull(result);
        verify(personaModel).chat(any(ChatRequest.class));
        verify(globalModel, never()).chat(any(ChatRequest.class));
    }

    @Test
    void nextStep_fallsBackToGlobal_whenModelResolutionFails() {
        when(modelRouter.resolve("unknown")).thenThrow(new RuntimeException("No provider"));
        when(globalModel.chat(any(ChatRequest.class))).thenReturn(response());

        ChatResponse result = adapter.nextStep(request(), "unknown");

        assertNotNull(result);
        verify(globalModel).chat(any(ChatRequest.class));
    }

    @Test
    void perModelCircuitBreaker_doesNotCountMalformedResponseAsFailure() {
        // LlmMalformedResponseException should NOT trip the circuit breaker
        LangChain4jLlmAdapter.PerModelCircuitBreaker cb =
                new LangChain4jLlmAdapter.PerModelCircuitBreaker("test#model");

        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenThrow(new LlmMalformedResponseException("bad schema", null));

        // Should throw but NOT trip circuit breaker
        assertThrows(LlmMalformedResponseException.class, () -> cb.execute(request(), model));

        // Circuit should still be closed — next call should succeed
        when(model.chat(any(ChatRequest.class))).thenReturn(response());
        ChatResponse result = cb.execute(request(), model);
        assertNotNull(result);
    }

    @Test
    void perModelCircuitBreaker_doesNotCountToolCallErrorAsFailure() {
        // LlmToolCallException should NOT trip the circuit breaker
        LangChain4jLlmAdapter.PerModelCircuitBreaker cb =
                new LangChain4jLlmAdapter.PerModelCircuitBreaker("test#model");

        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenThrow(new LlmToolCallException("bad tool call", null));

        assertThrows(LlmToolCallException.class, () -> cb.execute(request(), model));

        // Circuit should still be closed
        when(model.chat(any(ChatRequest.class))).thenReturn(response());
        ChatResponse result = cb.execute(request(), model);
        assertNotNull(result);
    }

    @Test
    void perModelCircuitBreaker_opensAfterThresholdFailures() {
        LangChain4jLlmAdapter.PerModelCircuitBreaker cb =
                new LangChain4jLlmAdapter.PerModelCircuitBreaker("test#model");

        ChatModel model = mock(ChatModel.class);
        LlmRateLimitException rateLimit = new LlmRateLimitException("rate limited", null);
        when(model.chat(any(ChatRequest.class))).thenThrow(rateLimit);

        // 4 requests with >50% failure should open the circuit
        for (int i = 0; i < 4; i++) {
            assertThrows(LlmRateLimitException.class, () -> cb.execute(request(), model));
        }

        // Next call should fail fast with circuit-open error (not the original exception)
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> cb.execute(request(), model));
        assertTrue(thrown.getMessage().contains("Circuit breaker OPEN"));
    }

    @Test
    void perModelCircuitBreaker_isolationBetweenKeys() {
        // Two separate circuit breakers should be independent
        LangChain4jLlmAdapter.PerModelCircuitBreaker cb1 =
                new LangChain4jLlmAdapter.PerModelCircuitBreaker("groq#model");
        LangChain4jLlmAdapter.PerModelCircuitBreaker cb2 =
                new LangChain4jLlmAdapter.PerModelCircuitBreaker("openai#model");

        ChatModel model1 = mock(ChatModel.class);
        ChatModel model2 = mock(ChatModel.class);

        LlmRateLimitException rateLimit = new LlmRateLimitException("rate limited", null);
        when(model1.chat(any(ChatRequest.class))).thenThrow(rateLimit);
        when(model2.chat(any(ChatRequest.class))).thenReturn(response());

        // Trip cb1
        for (int i = 0; i < 4; i++) {
            assertThrows(LlmRateLimitException.class, () -> cb1.execute(request(), model1));
        }

        // cb1 should be open, cb2 should still be closed
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> cb1.execute(request(), model1));
        assertTrue(thrown.getMessage().contains("Circuit breaker OPEN"));

        // cb2 should still work fine
        ChatResponse result = cb2.execute(request(), model2);
        assertNotNull(result);
    }
}
