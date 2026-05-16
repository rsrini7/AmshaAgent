package com.arcclaw.core.routing;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;

@ApplicationScoped
public class OpenAiLlmProviderFactory implements RoutingChatModelProvider {

    @Override public String providerId() { return "openai"; }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return ChatModelFactory.createFromConfig(config);
    }
}
