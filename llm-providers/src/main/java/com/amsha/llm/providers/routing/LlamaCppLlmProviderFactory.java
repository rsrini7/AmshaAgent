package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.providers.catalog.*;
import com.amsha.llm.providers.helper.*;
import com.amsha.llm.providers.reasoning.*;
import com.amsha.llm.providers.wrapper.*;

import io.quarkus.arc.Unremovable;

/**
 * Provider factory for llama.cpp server (OpenAI-compatible API at /v1/chat/completions).
 * No API key required — runs locally.
 */
@ApplicationScoped
@Unremovable
public class LlamaCppLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_LLAMACPP;
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
