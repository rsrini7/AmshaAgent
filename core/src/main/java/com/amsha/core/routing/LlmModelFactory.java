package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.amsha.core.agent.reasoning.StructuredOutputMode;

/**
 * Common factory for creating LangChain4j ChatModels based on provider configuration.
 * Consolidates the switch logic previously duplicated in IntentDetectorModelProducer and
 * CompactionModelProducer.
 */
@ApplicationScoped
public class LlmModelFactory {

    @Inject
    @Any
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
        return createModel(
                provider,
                baseUrl,
                apiKey,
                modelName,
                maxTokens,
                timeout,
                maxRetries,
                ollamaBaseUrl,
                ollamaModelId,
                StructuredOutputMode.NATIVE,
                false);
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
        return createModel(
                provider,
                baseUrl,
                apiKey,
                modelName,
                maxTokens,
                timeout,
                maxRetries,
                ollamaBaseUrl,
                ollamaModelId,
                structuredOutputMode,
                false);
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
        String effectiveModel = "ollama".equalsIgnoreCase(provider) ? ollamaModelId : modelName;

        RoutingChatModelProvider factory = factories.stream()
                .filter(f -> f.providerId().equalsIgnoreCase(provider))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No RoutingChatModelProvider registered for provider='" + provider + "'. Registered: "
                                + factories.stream()
                                        .map(RoutingChatModelProvider::providerId)
                                        .collect(Collectors.joining(", "))));

        LlmModelConfig config = new LlmModelConfig(
                provider.toLowerCase(Locale.ROOT),
                effectiveModel,
                effectiveBaseUrl,
                Optional.ofNullable(apiKey).filter(k -> !k.isBlank()),
                Optional.ofNullable(maxTokens),
                Optional.ofNullable(timeout),
                Optional.ofNullable(maxRetries),
                structuredOutputMode,
                toolless);

        return factory.create(config);
    }

    private static final Map<String, String> URL_PROVIDER_MAP = Map.of(
            "openai.com", ModelDefaults.PROVIDER_OPENAI,
            "groq.com", ModelDefaults.PROVIDER_GROQ,
            "nvidia.com", ModelDefaults.PROVIDER_NVIDIA,
            "cerebras.ai", ModelDefaults.PROVIDER_CEREBRAS,
            "sarvam.ai", ModelDefaults.PROVIDER_SARVAM,
            "opencode.ai", ModelDefaults.PROVIDER_OPENCODE,
            "huggingface.co", ModelDefaults.PROVIDER_HUGGINGFACE,
            "googleapis.com", ModelDefaults.PROVIDER_GEMINI,
            "localhost:11434", ModelDefaults.PROVIDER_OLLAMA,
            "localhost:9000", ModelDefaults.PROVIDER_LLAMACPP);

    public static String inferProviderFromUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return ModelDefaults.PROVIDER_OPENROUTER;
        return URL_PROVIDER_MAP.entrySet().stream()
                .filter(entry -> baseUrl.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(ModelDefaults.PROVIDER_OPENROUTER);
    }
}
