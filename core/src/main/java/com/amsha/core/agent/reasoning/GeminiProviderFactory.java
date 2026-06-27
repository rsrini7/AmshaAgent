package com.amsha.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkiverse.langchain4j.ai.runtime.gemini.AiGeminiChatLanguageModel;

@ApplicationScoped
public class GeminiProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "AMSHA_GEMINI_API_KEY")
    Optional<String> apiKey;

    @ConfigProperty(name = "AMSHA_GEMINI_MODEL", defaultValue = "gemini-2.5-flash-lite")
    String modelName;

    @ConfigProperty(name = "amsha.llm.gemini.log-requests", defaultValue = "false")
    boolean logRequests;

    @ConfigProperty(name = "amsha.llm.gemini.log-responses", defaultValue = "false")
    boolean logResponses;

    @Override
    public String providerName() {
        return "gemini";
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
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
        String key = apiKey.orElseThrow(() -> new IllegalStateException("Gemini requires AMSHA_GEMINI_API_KEY"));

        return AiGeminiChatLanguageModel.builder()
                .key(key)
                .modelId(overrideModel != null ? overrideModel : modelName)
                .temperature(agentConfig.temperature())
                .maxOutputTokens(agentConfig.maxTokens())
                .timeout(agentConfig.timeout())
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }
}
