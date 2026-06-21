package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

import io.quarkiverse.langchain4j.ai.runtime.gemini.AiGeminiChatLanguageModel;

/**
 * {@link RoutingChatModelProvider} for Google Gemini AI using the Quarkiverse implementation.
 */
@ApplicationScoped
public class GeminiLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return "gemini";
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        String key = config.apiKey()
                .orElseThrow(
                        () -> new IllegalStateException("Gemini provider requires an API key (AMSHA_LLM_API_KEY)"));

        // Hardening: Strip quotes and whitespace
        key = key.replaceAll("[\\p{Cntrl}\\s\"']", "").trim();

        return AiGeminiChatLanguageModel.builder()
                .key(key)
                .modelId(config.modelName())
                .temperature(0.0)
                // .includeThoughts(false)
                // .thinkingBudget(0L)
                .timeout(config.timeout().orElse(Duration.ofSeconds(30)))
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
