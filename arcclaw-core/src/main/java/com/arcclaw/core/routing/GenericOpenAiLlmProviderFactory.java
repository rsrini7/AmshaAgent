package com.arcclaw.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * {@link RoutingChatModelProvider} for any OpenAI-compatible endpoint.
 * Fully configurable via {@code ARCCLAW_GENERIC_OPENAI_BASE_URL},
 * {@code ARCCLAW_GENERIC_OPENAI_API_KEY}, and {@code ARCCLAW_GENERIC_OPENAI_MODEL}.
 */
@ApplicationScoped
public class GenericOpenAiLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return "generic-openai";
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return ChatModelFactory.createFromConfig(config);
    }
}
