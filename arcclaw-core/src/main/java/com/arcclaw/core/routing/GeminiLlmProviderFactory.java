package com.arcclaw.core.routing;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * {@link RoutingChatModelProvider} for Google Gemini AI using its OpenAI-compatible endpoint.
 */
@ApplicationScoped
public class GeminiLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return "gemini";
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        String baseUrl = config.baseUrl();
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.equals("default")) {
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/";
        }

        // Gemini's OpenAI-compatible endpoint requires the trailing slash to correctly 
        // append /chat/completions.
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }

        return OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(config.apiKey()
                .orElseThrow(() -> new IllegalStateException(
                    "Gemini provider requires an API key (ARCCLAW_LLM_API_KEY)")))
            .modelName(config.modelName())
            .maxTokens(config.maxTokens().orElse(null))
            .timeout(config.timeout().orElse(Duration.ofSeconds(30)))
            .maxRetries(config.maxRetries().orElse(3))
            .temperature(0.0)
            .logRequests(false)
            .logResponses(false)
            .build();
    }
}
