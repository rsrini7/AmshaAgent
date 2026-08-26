package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import com.amsha.core.agent.reasoning.port.LlmProviderPort;
import com.amsha.core.governance.BudgetExceededException;
import com.amsha.core.governance.BudgetWarningException;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.llm.api.error.LlmContentFilterException;
import com.amsha.llm.api.error.LlmRateLimitException;

import org.junit.jupiter.api.Test;

/**
 * The processor preserves the schema as request-scoped data and delegates
 * capability resolution and native attachment to the provider port.
 */
class StructuredOutputProcessorSchemaTest {

    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"]}";

    private static ChatResponse response() {
        return ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage("{\"answer\":\"ok\"}"))
                .build();
    }

    private record Fixture(
            StructuredOutputProcessor processor,
            LlmProviderPort port,
            GovernanceStrategy governanceStrategy,
            AgentOtelContext otelContext) {}

    private static Fixture fixture() {
        SystemPromptBuilder promptBuilder = mock(SystemPromptBuilder.class);
        when(promptBuilder.build(any(), any(), any(), any(), any(Boolean.class)))
                .thenReturn("sys");
        LlmProviderPort port = mock(LlmProviderPort.class);
        when(port.nextStep(any(ChatRequest.class), anyString(), any(String.class)))
                .thenReturn(response());
        when(port.nextStep(any(ChatRequest.class), anyString(), isNull(String.class)))
                .thenReturn(response());
        GovernanceStrategy gov = mock(GovernanceStrategy.class);
        AgentOtelContext otel = mock(AgentOtelContext.class);
        StructuredOutputProcessor processor = new StructuredOutputProcessor(promptBuilder, port, gov, otel);
        return new Fixture(processor, port, gov, otel);
    }

    @Test
    void forwardsRawSchemaToRouteAwareProviderPort() {
        Fixture f = fixture();

        f.processor().chatForStructuredOutput("s1", "coder", "prompt", SCHEMA);

        verify(f.port()).nextStep(any(ChatRequest.class), eq("coder"), eq(SCHEMA));
    }

    @Test
    void legacyStructuredCallUsesRouteAwarePortWithoutSchema() {
        Fixture f = fixture();

        f.processor().chatForStructuredOutput("s1", "coder", "prompt");

        verify(f.port()).nextStep(any(ChatRequest.class), eq("coder"), isNull(String.class));
    }

    @Test
    void recordsTokenUsage_whenPresentInResponse() throws Exception {
        Fixture f = fixture();
        ChatResponse respWithUsage = ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage("{\"answer\":\"ok\"}"))
                .tokenUsage(new TokenUsage(10, 20))
                .build();
        when(f.port().nextStep(any(ChatRequest.class), anyString(), any(String.class)))
                .thenReturn(respWithUsage);

        String result = f.processor().chatForStructuredOutput("s1", "coder", "prompt", SCHEMA);
        assertEquals("{\"answer\":\"ok\"}", result);
        verify(f.governanceStrategy()).recordLlmCall("s1", 10, 20);
        verify(f.otelContext()).recordLlmCall("s1", 10, 20);
    }

    @Test
    void handlesBudgetWarning_gracefully() throws Exception {
        Fixture f = fixture();
        ChatResponse respWithUsage = ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage("{\"answer\":\"ok\"}"))
                .tokenUsage(new TokenUsage(10, 20))
                .build();
        when(f.port().nextStep(any(ChatRequest.class), anyString(), any(String.class)))
                .thenReturn(respWithUsage);
        doThrow(new BudgetWarningException("Approaching budget limit"))
                .when(f.governanceStrategy())
                .recordLlmCall(anyString(), anyInt(), anyInt());

        String result = f.processor().chatForStructuredOutput("s1", "coder", "prompt", SCHEMA);
        assertEquals("{\"answer\":\"ok\"}", result);
    }

    @Test
    void throwsBudgetExceededException_whenBudgetExceeded() throws Exception {
        Fixture f = fixture();
        ChatResponse respWithUsage = ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage("{\"answer\":\"ok\"}"))
                .tokenUsage(new TokenUsage(10, 20))
                .build();
        when(f.port().nextStep(any(ChatRequest.class), anyString(), any(String.class)))
                .thenReturn(respWithUsage);
        doThrow(new BudgetExceededException("Budget exceeded"))
                .when(f.governanceStrategy())
                .recordLlmCall(anyString(), anyInt(), anyInt());

        assertThrows(
                BudgetExceededException.class,
                () -> f.processor().chatForStructuredOutput("s1", "coder", "prompt", SCHEMA));
    }

    @Test
    void rateLimitException_isWrappedInRuntimeException() {
        Fixture f = fixture();
        when(f.port().nextStep(any(ChatRequest.class), anyString(), any(String.class)))
                .thenThrow(new LlmRateLimitException("Too many requests", new RuntimeException()));

        RuntimeException ex = assertThrows(
                RuntimeException.class, () -> f.processor().chatForStructuredOutput("s1", "coder", "prompt", SCHEMA));
        assertTrue(ex.getMessage().contains("Structured output LLM call failed"));
    }

    @Test
    void providerException_isWrappedInRuntimeException() {
        Fixture f = fixture();
        when(f.port().nextStep(any(ChatRequest.class), anyString(), any(String.class)))
                .thenThrow(new LlmContentFilterException("Generic provider error", new RuntimeException()));

        RuntimeException ex = assertThrows(
                RuntimeException.class, () -> f.processor().chatForStructuredOutput("s1", "coder", "prompt", SCHEMA));
        assertTrue(ex.getMessage().contains("Structured output LLM call failed"));
    }
}
