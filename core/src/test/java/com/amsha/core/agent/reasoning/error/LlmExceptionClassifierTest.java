package com.amsha.core.agent.reasoning.error;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.exception.HttpException;

import com.amsha.core.util.AgentErrorMessageUtil;

import org.junit.jupiter.api.Test;

class LlmExceptionClassifierTest {

    @Test
    void testNvidiaDegradedError() {
        String msg =
                "{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"Function id '7fe236cd-dab4-40d4-a139-b28d6673ffd3': DEGRADED function cannot be invoked\"}";
        HttpException httpEx = new HttpException(400, msg);

        LlmProviderException classified = LlmExceptionClassifier.classify(httpEx);

        assertTrue(classified instanceof LlmServiceUnavailableException);
        assertTrue(classified.getMessage().contains("DEGRADED"));
        assertFalse(classified.getMessage().contains("Unclassified error"), "Should not be unclassified");
    }

    @Test
    void testGroqRateLimit() {
        String msg =
                "{\"error\":{\"message\":\"Rate limit reached for model meta-llama/llama-4-scout-17b-16e-instruct in organization org_01kj3acrf4frvbvqxnax4c9mth service tier on_demand on tokens per minute (TPM): Limit 30000, Used 22218, Requested 12526. Please try again in 9.488s.\",\"type\":\"tokens\",\"code\":\"rate_limit_exceeded\"}}";
        HttpException httpEx = new HttpException(429, msg);

        LlmProviderException classified = LlmExceptionClassifier.classify(httpEx);

        assertTrue(classified instanceof LlmRateLimitException);
        assertTrue(classified.getMessage().contains("Rate limit"));
    }

    @Test
    void testSanitizeDetailKey() {
        String msg =
                "{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"Function id '123': DEGRADED function cannot be invoked\"}";
        String sanitized = AgentErrorMessageUtil.sanitizeErrorMessage(msg);

        // Should extract the detail part and wrap it in API Error: %s
        assertTrue(sanitized.contains("Function id '123'"));
        assertTrue(sanitized.startsWith("⚠️ API Error:"));
    }

    @Test
    void testGeminiSafetyBlockedFilter() {
        NullPointerException npe = new NullPointerException("Something about parts list being null or Part");
        LlmProviderException classified = LlmExceptionClassifier.classify(npe);
        assertTrue(classified instanceof LlmContentFilterException);
        assertTrue(classified.getMessage().contains("safety filters"));
    }

    @Test
    void testAuthenticationExceptionFor401() {
        HttpException httpEx = new HttpException(401, "Unauthorized");
        LlmProviderException classified = LlmExceptionClassifier.classify(httpEx);
        assertTrue(classified instanceof LlmAuthenticationException);
    }

    @Test
    void testModelNotFoundFor404() {
        HttpException httpEx = new HttpException(404, "Not Found");
        LlmProviderException classified = LlmExceptionClassifier.classify(httpEx);
        assertTrue(classified instanceof LlmAuthenticationException);
        assertTrue(classified.getMessage().contains("Model not found"));
    }

    @Test
    void testMalformedRestClientUrl() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid REST Client URL: chat/completions");
        LlmProviderException classified = LlmExceptionClassifier.classify(ex);
        assertTrue(classified instanceof LlmServiceUnavailableException);
        assertTrue(classified.getMessage().contains("Malformed LLM Base URL"));
    }

    @Test
    void testTimeoutError() {
        RuntimeException ex = new RuntimeException("LLM Request timed out after 30000 ms");
        LlmProviderException classified = LlmExceptionClassifier.classify(ex);
        assertTrue(classified instanceof LlmServiceUnavailableException);
        assertTrue(classified.getMessage().contains("Timeout"));
    }
}
