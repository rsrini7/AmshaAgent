package com.amsha.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.core.routing.ModelDefaults;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OllamaProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url", defaultValue = ModelDefaults.DEFAULT_OLLAMA_BASE_URL)
    String baseUrl;

    @ConfigProperty(
            name = "quarkus.langchain4j.ollama.chat-model.model-id",
            defaultValue = ModelDefaults.DEFAULT_OLLAMA_MODEL)
    String modelId;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_OLLAMA;
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
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(overrideModel != null ? overrideModel : modelId)
                .logRequests(agentConfig.logHttp())
                .logResponses(agentConfig.logHttp())
                .build();
    }
}
