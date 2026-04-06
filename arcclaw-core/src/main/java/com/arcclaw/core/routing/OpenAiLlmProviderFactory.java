package com.arcclaw.core.routing;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

@ApplicationScoped
public class OpenAiLlmProviderFactory implements RoutingChatModelProvider {

    @Override public String providerId() { return "openai"; }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return OpenAiChatModel.builder()
            .baseUrl(config.baseUrl())
            .apiKey(config.apiKey().orElseThrow(() -> new IllegalStateException("OpenAI requires apiKey")))
            .modelName(config.modelName())
            .maxTokens(config.maxTokens().orElse(null))
            .timeout(config.timeout().orElse(null))
            .maxRetries(config.maxRetries().orElse(3))
            .temperature(0.0)
            .logRequests(false)
            .logResponses(false)
            .build();
    }
}
