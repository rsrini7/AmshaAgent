package com.arcclaw.app.routing;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

@ApplicationScoped
public class OllamaLlmProviderFactory implements LlmProviderFactory {

    @Override public String providerId() { return "ollama"; }
    @Override public boolean requiresApiKey() { return false; }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return OllamaChatModel.builder()
            .baseUrl(config.baseUrl())
            .modelName(config.modelName())
            .build();
    }
}
