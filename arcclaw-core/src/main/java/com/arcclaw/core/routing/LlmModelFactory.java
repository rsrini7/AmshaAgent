package com.arcclaw.core.routing;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.StructuredOutputMode;
import dev.langchain4j.model.chat.ChatModel;

/**
 * Common factory for creating LangChain4j ChatModels based on provider configuration.
 * Consolidates the switch logic previously duplicated in IntentDetectorModelProducer and
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
            Integer maxRetries,
            String ollamaBaseUrl,
            String ollamaModelId) {
        return createModel(provider, baseUrl, apiKey, modelName, maxTokens, timeout, maxRetries, 
                ollamaBaseUrl, ollamaModelId, StructuredOutputMode.NATIVE, false);
    }

    public ChatModel createModel(
            String provider,
            String baseUrl,
            String apiKey,
            String modelName,
            Integer maxTokens,
            Duration timeout,
            Integer maxRetries,
            String ollamaBaseUrl,
            String ollamaModelId,
            StructuredOutputMode structuredOutputMode) {
        return createModel(provider, baseUrl, apiKey, modelName, maxTokens, timeout, maxRetries, 
                ollamaBaseUrl, ollamaModelId, structuredOutputMode, false);
    }

    public ChatModel createModel(
            String provider,
            String baseUrl,
            String apiKey,
            String modelName,
            Integer maxTokens,
            Duration timeout,
            Integer maxRetries,
            String ollamaBaseUrl,
            String ollamaModelId,
            StructuredOutputMode structuredOutputMode,
            boolean toolless) {

        String effectiveBaseUrl = "ollama".equalsIgnoreCase(provider) ? ollamaBaseUrl : baseUrl;
        String effectiveModel   = "ollama".equalsIgnoreCase(provider) ? ollamaModelId : modelName;

        RoutingChatModelProvider factory = factories.stream()
            .filter(f -> f.providerId().equalsIgnoreCase(provider))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No RoutingChatModelProvider registered for provider='" + provider + "'. Registered: " + 
                factories.stream().map(RoutingChatModelProvider::providerId).collect(Collectors.joining(", "))));

        LlmModelConfig config = new LlmModelConfig(
            provider.toLowerCase(),
            effectiveModel,
            effectiveBaseUrl,
            Optional.ofNullable(apiKey).filter(k -> !k.isBlank()),
            Optional.ofNullable(maxTokens),
            Optional.ofNullable(timeout),
            Optional.ofNullable(maxRetries),
            structuredOutputMode,
            toolless
        );

        return factory.create(config);
    }

    private static final Map<String, String> URL_PROVIDER_MAP = Map.of(
        "openai.com", "openai",
        "groq.com", "groq",
        "nvidia.com", "nvidia",
        "cerebras.ai", "cerebras",
        "googleapis.com", "gemini",
        "localhost:11434", "ollama"
    );

    public static String inferProviderFromUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "openrouter";
        return URL_PROVIDER_MAP.entrySet().stream()
            .filter(entry -> baseUrl.contains(entry.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse("openrouter");
    }
}
