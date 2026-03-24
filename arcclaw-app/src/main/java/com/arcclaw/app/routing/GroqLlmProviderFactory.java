package com.arcclaw.app.routing;

import jakarta.enterprise.context.ApplicationScoped;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

@ApplicationScoped
public class GroqLlmProviderFactory implements LlmProviderFactory {

    @Override public String providerId() { return "groq"; }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return OpenAiChatModel.builder()
            .baseUrl(config.baseUrl())
            .apiKey(config.apiKey().orElseThrow(() -> new IllegalStateException("Groq requires apiKey")))
            .modelName(config.modelName())
            .maxTokens(config.maxTokens().orElse(null))
            .timeout(config.timeout().orElse(null))
            .temperature(0.0)
            .logRequests(false)
            .logResponses(false)
            .build();
    }
}
