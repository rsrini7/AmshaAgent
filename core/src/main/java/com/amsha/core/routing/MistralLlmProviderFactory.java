package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

/**
 * {@link RoutingChatModelProvider} for Mistral AI / La Plateforme.
 *
 * <p>Mistral accepts standard OpenAI chat completion wire format with bearer authentication.
 */
@ApplicationScoped
public class MistralLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_MISTRAL;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        var builder = ChatModelFactory.openAiBuilder(
                config.baseUrl(),
                config.apiKey()
                        .orElseThrow(() -> new IllegalStateException(
                                "Mistral provider requires an API key (AMSHA_MISTRAL_API_KEY)")),
                config.modelName(),
                config.maxTokens().orElse(null),
                config.timeout().orElse(Duration.ofSeconds(30)),
                config.maxRetries().orElse(3),
                0.0,
                false,
                false);

        ChatModelFactory.applyJsonResponseFormat(
                builder, config.structuredOutputMode(), config.toolless(), config.baseUrl());

        return builder.build();
    }
}
