package com.arcclaw.app.routing;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * {@link RoutingChatModelProvider} for the Groq inference API.
 * Groq uses the OpenAI API protocol but with its own base URL and key.
 */
@ApplicationScoped
public class GroqLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return "groq";
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return OpenAiChatModel.builder()
            .baseUrl(config.baseUrl())
            .apiKey(config.apiKey()
                .orElseThrow(() -> new IllegalStateException(
                    "Groq provider requires an API key (ARCCLAW_LLM_API_KEY)")))
            .modelName(config.modelName())
            .maxTokens(config.maxTokens().orElse(null))
            .timeout(config.timeout().orElse(Duration.ofSeconds(30)))
            .temperature(0.0)
            .logRequests(false)
            .logResponses(false)
            .build();
    }
}
