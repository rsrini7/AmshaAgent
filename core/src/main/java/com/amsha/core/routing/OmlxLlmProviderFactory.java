package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OmlxLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_OMLX;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return ChatModelFactory.createFromConfig(config);
    }
}
