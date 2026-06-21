package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

/**
 * {@link RoutingChatModelProvider} for any OpenAI-compatible endpoint.
 * Fully configurable via {@code AMSHA_GENERIC_OPENAI_BASE_URL},
 * {@code AMSHA_GENERIC_OPENAI_API_KEY}, and {@code AMSHA_GENERIC_OPENAI_MODEL}.
 */
@ApplicationScoped
public class GenericOpenAiLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return "generic-openai";
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        var builder = ChatModelFactory.openAiBuilder(
                config.baseUrl(),
                config.apiKey()
                        .orElseThrow(() -> new IllegalStateException("generic-openai provider requires an API key")),
                config.modelName(),
                config.maxTokens().orElse(null),
                config.timeout().orElse(null),
                config.maxRetries().orElse(3),
                0.0,
                false,
                false);

        Map<String, Object> custom = ChatModelFactory.deepSeekWorkaroundParams(config.modelName());
        if (!custom.isEmpty()) {
            builder.defaultRequestParameters(OpenAiChatRequestParameters.builder()
                    .customParameters(custom)
                    .build());
        }

        return builder.build();
    }
}
