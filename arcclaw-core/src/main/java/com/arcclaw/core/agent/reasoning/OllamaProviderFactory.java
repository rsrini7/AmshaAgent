package com.arcclaw.core.agent.reasoning;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OllamaProviderFactory implements LlmProviderFactory {

    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url", defaultValue = "http://localhost:11434")
    String baseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.chat-model.model-id", defaultValue = "llama3.2")
    String modelId;

    @Override
    public String providerName() {
        return "ollama";
    }

    @Override
    public ChatModel create() {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelId)
                .build();
    }
}
