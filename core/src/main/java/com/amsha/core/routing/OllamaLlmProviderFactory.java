package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OllamaLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_OLLAMA;
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return OllamaChatModel.builder()
                .baseUrl(config.baseUrl())
                .modelName(config.modelName())
                .build();
    }
}
