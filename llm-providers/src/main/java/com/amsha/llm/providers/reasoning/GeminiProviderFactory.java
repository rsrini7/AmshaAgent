package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.StructuredOutputMode;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class GeminiProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "AMSHA_GEMINI_API_KEY")
    Optional<String> apiKey;

    @ConfigProperty(name = "AMSHA_GEMINI_MODEL", defaultValue = ModelDefaults.DEFAULT_GEMINI_MODEL)
    String modelName;

    @ConfigProperty(name = "amsha.llm.gemini.log-requests", defaultValue = "false")
    boolean logRequests;

    @ConfigProperty(name = "amsha.llm.gemini.log-responses", defaultValue = "false")
    boolean logResponses;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_GEMINI;
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

        return GoogleAiGeminiChatModel.builder()
                .apiKey(key)
                .modelName(overrideModel != null ? overrideModel : modelName)
                .temperature(agentConfig.temperature())
                .maxOutputTokens(agentConfig.maxTokens())
                .timeout(agentConfig.timeout())
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }
}
