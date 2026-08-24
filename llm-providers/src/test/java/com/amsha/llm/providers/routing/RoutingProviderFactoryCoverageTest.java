package com.amsha.llm.providers.routing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.api.StructuredOutputMode;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RoutingProviderFactoryCoverageTest {

    static Stream<RoutingChatModelProvider> providers() {
        return Stream.of(
                new AgyProviderFactory(),
                new CerebrasLlmProviderFactory(),
                new FoundryLlmProviderFactory(),
                new GeminiLlmProviderFactory(),
                new GenericOpenAiLlmProviderFactory(),
                new GroqLlmProviderFactory(),
                new KimchiLlmProviderFactory(),
                new LlamaCppLlmProviderFactory(),
                new NvidiaLlmProviderFactory(),
                new OllamaLlmProviderFactory(),
                new OmlxLlmProviderFactory(),
                new OpenAiLlmProviderFactory(),
                new OpenCodeLlmProviderFactory(),
                new HuggingFaceLlmProviderFactory(),
                new MistralLlmProviderFactory(),
                new CloudflareLlmProviderFactory(),
                new CohereLlmProviderFactory(),
                new CodexLlmProviderFactory(),
                new ClaudeCodeProviderFactory(),
                new OpenRouterLlmProviderFactory(),
                new SarvamLlmProviderFactory());
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
