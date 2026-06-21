package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Provider factory for llama.cpp server (OpenAI-compatible API at /v1/chat/completions).
 * No API key required — runs locally.
 */
@ApplicationScoped
public class LlamaCppLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return "llamacpp";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey("not-needed") // llama.cpp doesn't require auth
                .modelName(config.modelName())
                .temperature(0.7)
                .build();
    }
}
