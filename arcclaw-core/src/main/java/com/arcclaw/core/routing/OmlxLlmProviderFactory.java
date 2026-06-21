package com.arcclaw.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OmlxLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return "omlx";
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return ChatModelFactory.createFromConfig(config);
    }
}
