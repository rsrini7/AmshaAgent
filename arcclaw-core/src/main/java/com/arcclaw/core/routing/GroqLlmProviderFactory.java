package com.arcclaw.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

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
        var builder = ChatModelFactory.openAiBuilder(
                config.baseUrl(),
                config.apiKey()
                        .orElseThrow(() ->
                                new IllegalStateException("Groq provider requires an API key (ARCCLAW_LLM_API_KEY)")),
                config.modelName(),
                config.maxTokens().orElse(null),
                config.timeout().orElse(Duration.ofSeconds(30)),
                config.maxRetries().orElse(3),
                0.0,
                false,
                false);

        ChatModelFactory.applyJsonResponseFormat(builder, config.structuredOutputMode(), config.toolless());

        return builder.build();
    }
}
