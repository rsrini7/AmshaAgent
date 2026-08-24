package com.amsha.core.agent.reasoning.error;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.exception.HttpException;

import java.util.List;

import com.amsha.llm.api.error.LlmAuthenticationException;
import com.amsha.llm.api.error.LlmExceptionClassifier;
import com.amsha.llm.api.error.LlmProviderException;
import com.amsha.llm.api.error.LlmRateLimitException;
import com.amsha.llm.api.error.LlmServiceUnavailableException;

import org.junit.jupiter.api.Test;

/**
 * Behavior-equivalence suite for the LLM exception taxonomy after the move to
 * {@code com.amsha.llm.api.error}: the same raw-exception inputs must classify into the
 * same normalized types as the pre-move hard-wired chain (framework + generic tiers).
 * The provider-specific tier is covered by {@code GeminiExceptionClassifierTest} in
 * amsha-llm-providers.
 */
class LlmErrorClassificationServiceTest {

    private final LlmErrorClassificationService service = new LlmErrorClassificationService(
            List.of(new LangChain4jExceptionClassifier(), new GenericExceptionClassifier()));

    @Test
    void testNvidiaDegradedError() {
        String msg =
                "{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"Function id '7fe236cd-dab4-40d4-a139-b28d6673ffd3': DEGRADED function cannot be invoked\"}";
        HttpException httpEx = new HttpException(400, msg);

        LlmProviderException classified = service.doClassify(httpEx);

        assertTrue(classified instanceof LlmServiceUnavailableException);
        assertTrue(classified.getMessage().contains("DEGRADED"));
    }

    @Test
    void testGroqRateLimit() {
        String msg =
                "{\"error\":{\"message\":\"Rate limit reached for model meta-llama/llama-4-scout-17b-16e-instruct in tokens per minute (TPM): Limit 30000, Used 22218, Requested 12526. Please try again in 9.488s.\",\"type\":\"tokens\",\"code\":\"rate_limit_exceeded\"}}";
        HttpException httpEx = new HttpException(429, msg);

        LlmProviderException classified = service.doClassify(httpEx);

        assertTrue(classified instanceof LlmRateLimitException);
        assertTrue(classified.getMessage().contains("Rate limit"));
    }

    @Test
    void testAuthenticationExceptionFor401() {
        HttpException httpEx = new HttpException(401, "Unauthorized");
        assertTrue(service.doClassify(httpEx) instanceof LlmAuthenticationException);
    }

    @Test
    void testModelNotFoundFor404() {
        HttpException httpEx = new HttpException(404, "Not Found");
        LlmProviderException classified = service.doClassify(httpEx);
        assertTrue(classified instanceof LlmAuthenticationException);
        assertTrue(classified.getMessage().contains("Model not found"));
    }

    @Test
    void testMalformedRestClientUrl() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid REST Client URL: chat/completions");
        LlmProviderException classified = service.doClassify(ex);
        assertTrue(classified instanceof LlmServiceUnavailableException);
        assertTrue(classified.getMessage().contains("Malformed LLM Base URL"));
    }

    @Test
    void testTimeoutError() {
        RuntimeException ex = new RuntimeException("LLM Request timed out after 30000 ms");
        LlmProviderException classified = service.doClassify(ex);
        assertTrue(classified instanceof LlmServiceUnavailableException);
        assertTrue(classified.getMessage().contains("Timeout"));
    }

    @Test
    void alreadyClassifiedThrowablesPassThroughUnchanged() {
        LlmRateLimitException existing = new LlmRateLimitException("already typed");
        assertEquals(existing, service.doClassify(existing));
    }

    @Test
    void staticGatewayPassesTypedExceptionThroughEvenWithoutCdi() {
        // Regression: the outside-CDI fallback must not send normalized exceptions
        // through the generic chain (P2 review fix).
        LlmRateLimitException existing = new LlmRateLimitException("already typed");
        assertEquals(existing, LlmErrorClassificationService.classify(existing));
    }

    @Test
    void unrecognizedThrowableYieldsServiceUnavailableWhenGenericClassifierInChain() {
        LlmProviderException classified = service.doClassify(new Exception("totally unknown failure mode"));
        assertTrue(classified instanceof LlmServiceUnavailableException);
        assertTrue(classified.getMessage().contains("Unclassified error"));
    }

    @Test
    void unrecognizedThrowableYieldsNullWhenNoMatchingClassifier() {
        var emptyService = new LlmErrorClassificationService(List.of());
        assertNull(emptyService.doClassify(new Exception("totally unknown failure mode")));
    }

    @Test
    void staticGatewayUsesCoreLocalFallbackOutsideCdi() {
        // Outside the container the gateway falls back to the core-local chain.
        HttpException httpEx = new HttpException(401, "Unauthorized");
        assertTrue(LlmErrorClassificationService.classify(httpEx) instanceof LlmAuthenticationException);
    }

    @Test
    void orderDeterminesChainSequence() {
        LlmExceptionClassifier first = new LlmExceptionClassifier() {
            @Override
            public LlmProviderException classify(Throwable t) {
                return new LlmRateLimitException("first-wins");
            }

            @Override
            public int order() {
                return -10;
            }
        };
        LlmExceptionClassifier last = new LlmExceptionClassifier() {
            @Override
            public LlmProviderException classify(Throwable t) {
                return new LlmServiceUnavailableException("last-never-reached");
            }
        };

        var service = new LlmErrorClassificationService(List.of(last, first));
        assertEquals("first-wins", service.doClassify(new Exception("x")).getMessage());
        assertEquals(2, service.classifiers().size());
    }
}
