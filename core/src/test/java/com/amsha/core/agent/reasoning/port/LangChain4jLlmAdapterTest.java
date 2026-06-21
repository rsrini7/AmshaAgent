package com.amsha.core.agent.reasoning.port;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.request.ChatRequest;

import java.lang.reflect.Method;
import java.util.Arrays;

import com.amsha.core.agent.reasoning.error.LlmMalformedResponseException;
import com.amsha.core.agent.reasoning.error.LlmToolCallException;

import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.junit.jupiter.api.Test;

class LangChain4jLlmAdapterTest {

    @Test
    void nextStepWithCb_doesNotCountModelSchemaOrToolCallErrorsAsCircuitBreakerFailures() throws Exception {
        Method method = LangChain4jLlmAdapter.class.getDeclaredMethod("nextStepWithCb", ChatRequest.class);
        CircuitBreaker annotation = method.getAnnotation(CircuitBreaker.class);

        assertTrue(annotation != null, "nextStepWithCb should have a circuit breaker annotation");
        assertTrue(
                Arrays.asList(annotation.skipOn()).contains(LlmMalformedResponseException.class),
                "Schema-validation errors should not trip the LLM circuit breaker");
        assertTrue(
                Arrays.asList(annotation.skipOn()).contains(LlmToolCallException.class),
                "Tool-call formatting errors should not trip the LLM circuit breaker");
    }
}
