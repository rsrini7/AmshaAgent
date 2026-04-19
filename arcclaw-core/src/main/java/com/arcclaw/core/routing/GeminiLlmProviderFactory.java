package com.arcclaw.core.routing;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;

/**
 * {@link RoutingChatModelProvider} for Google Gemini AI using its native SDK.
 */
@ApplicationScoped
public class GeminiLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return "gemini";
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return GoogleAiGeminiChatModel.builder()
            .apiKey(config.apiKey()
                .orElseThrow(() -> new IllegalStateException(
                    "Gemini provider requires an API key (ARCCLAW_LLM_API_KEY)")))
            .modelName(config.modelName())
            .maxOutputTokens(config.maxTokens().orElse(null))
            .timeout(config.timeout().orElse(Duration.ofSeconds(30)))
            .maxRetries(config.maxRetries().orElse(3))
            .temperature(0.0)
            .logRequests(false)
            .logResponses(false)
            .build();
    }
}
