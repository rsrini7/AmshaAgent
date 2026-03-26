package com.arcclaw.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class GroqProviderFactory implements LlmProviderFactory {

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    String apiKey;

    @ConfigProperty(name = "quarkus.langchain4j.openai.base-url")
    String baseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name")
    String modelName;

    @Override
    public String providerName() {
        return "groq";
    }

    @Override
    public ChatModel create() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }
}
