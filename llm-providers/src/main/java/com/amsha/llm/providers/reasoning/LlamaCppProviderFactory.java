package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.catalog.*;
import com.amsha.llm.providers.helper.*;
import com.amsha.llm.providers.routing.*;
import com.amsha.llm.providers.wrapper.*;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * Agent-level provider for llama.cpp server (OpenAI-compatible API).
 * Used by the infinite-loop agent for main reasoning.
 */
@ApplicationScoped
@Unremovable
public class LlamaCppProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "AMSHA_LLAMACPP_BASE_URL", defaultValue = ModelDefaults.DEFAULT_LLAMACPP_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = "AMSHA_LLAMACPP_MODEL", defaultValue = ModelDefaults.DEFAULT_LLAMACPP_MODEL)
    String modelId;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_LLAMACPP;
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public ChatModel create() {
        return doCreate(null);
    }

    @Override
    public ChatModel create(String customModelName) {
        return doCreate(customModelName);
    }

    private ChatModel doCreate(String overrideModel) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey("not-needed")
                .modelName(overrideModel != null ? overrideModel : modelId)
                .logRequests(agentConfig.logHttp())
                .logResponses(agentConfig.logHttp())
                .temperature(0.7)
                .build();
    }
}
