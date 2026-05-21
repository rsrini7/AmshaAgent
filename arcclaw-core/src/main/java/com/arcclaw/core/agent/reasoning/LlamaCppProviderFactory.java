package com.arcclaw.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Agent-level provider for llama.cpp server (OpenAI-compatible API).
 * Used by the infinite-loop agent for main reasoning.
 */
@ApplicationScoped
public class LlamaCppProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "llamacpp.base-url", defaultValue = "http://localhost:9000")
    String baseUrl;

    @ConfigProperty(name = "llamacpp.model", defaultValue = "qwen3")
    String modelId;

    @Override
    public String providerName() {
        return "llamacpp";
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
    }

    @Override
    public ChatModel create() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey("not-needed")
                .modelName(modelId)
                .logRequests(agentConfig.logHttp())
                .logResponses(agentConfig.logHttp())
                .temperature(0.7)
                .build();
    }
}
