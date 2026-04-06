package com.arcclaw.core.routing;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import dev.langchain4j.model.chat.ChatModel;

/**
 * Common factory for creating LangChain4j ChatModels based on provider configuration.
 * Consolidates the switch logic previously duplicated in ModelProducer and
 * CompactionModelProducer.
 */
@ApplicationScoped
public class LlmModelFactory {

    @Inject @Any
    Instance<RoutingChatModelProvider> factories;

    public ChatModel createModel(
            String provider,
            String baseUrl,
            String apiKey,
            String modelName,
            Integer maxTokens,
            Duration timeout,
            String ollamaBaseUrl,
            String ollamaModelId) {

        // ← THIS IS THE OMITTED LOGIC — Ollama overrides both base-url and model name
        String effectiveBaseUrl = "ollama".equalsIgnoreCase(provider) ? ollamaBaseUrl : baseUrl;
        String effectiveModel   = "ollama".equalsIgnoreCase(provider) ? ollamaModelId : modelName;

        RoutingChatModelProvider factory = factories.stream()
            .filter(f -> f.providerId().equalsIgnoreCase(provider))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No RoutingChatModelProvider registered for provider='" + provider +
                "'. Registered: " + factories.stream()
                    .map(RoutingChatModelProvider::providerId)
                    .collect(Collectors.joining(", "))));

        LlmModelConfig config = new LlmModelConfig(
            provider.toLowerCase(),
            effectiveModel,
            effectiveBaseUrl,
            Optional.ofNullable(apiKey).filter(k -> !k.isBlank()),
            Optional.ofNullable(maxTokens),
            Optional.ofNullable(timeout)
        );

        return factory.create(config);
    }
}
