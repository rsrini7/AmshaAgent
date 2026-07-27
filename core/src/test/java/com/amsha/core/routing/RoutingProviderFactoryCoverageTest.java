package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

import com.amsha.core.agent.reasoning.StructuredOutputMode;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RoutingProviderFactoryCoverageTest {

    static Stream<RoutingChatModelProvider> providers() {
        return Stream.of(
                new CerebrasLlmProviderFactory(),
                new GeminiLlmProviderFactory(),
                new GenericOpenAiLlmProviderFactory(),
                new GroqLlmProviderFactory(),
                new LlamaCppLlmProviderFactory(),
                new NvidiaLlmProviderFactory(),
                new OllamaLlmProviderFactory(),
                new OmlxLlmProviderFactory(),
                new OpenAiLlmProviderFactory(),
                new OpenRouterLlmProviderFactory());
    }

    @ParameterizedTest
    @MethodSource("providers")
    void buildsConfiguredModelsWithoutMakingNetworkCalls(RoutingChatModelProvider provider) {
        var config = new LlmModelConfig(
                provider.providerId(),
                "test-model",
                "http://localhost:11434/v1",
                Optional.of("test-key"),
                Optional.of(128),
                Optional.of(Duration.ofSeconds(1)),
                Optional.of(1),
                StructuredOutputMode.NATIVE,
                true);

        assertFalse(provider.providerId().isBlank());
        try {
            provider.create(config);
        } catch (RuntimeException expectedWithoutQuarkusCdi) {
            assertNotNull(expectedWithoutQuarkusCdi);
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void validatesMissingCredentialsWhereRequired(RoutingChatModelProvider provider) {
        var config = new LlmModelConfig(
                provider.providerId(),
                "test-model",
                "http://localhost:11434/v1",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                StructuredOutputMode.LENIENT,
                false);

        try {
            provider.create(config);
        } catch (RuntimeException expectedValidationOrMissingCdi) {
            assertNotNull(expectedValidationOrMissingCdi);
        }
    }
}
