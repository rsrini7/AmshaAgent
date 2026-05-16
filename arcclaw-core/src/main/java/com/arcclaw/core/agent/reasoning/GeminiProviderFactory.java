package com.arcclaw.core.agent.reasoning;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.ai.runtime.gemini.AiGeminiChatLanguageModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class GeminiProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "ARCCLAW_GEMINI_API_KEY")
    Optional<String> apiKey;

    @ConfigProperty(name = "ARCCLAW_GEMINI_MODEL",
                    defaultValue = "gemini-2.5-flash-lite")
    String modelName;

    @ConfigProperty(name = "arcclaw.llm.gemini.log-requests", defaultValue = "false")
    boolean logRequests;

    @ConfigProperty(name = "arcclaw.llm.gemini.log-responses", defaultValue = "false")
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
        String key = apiKey.orElseThrow(() -> new IllegalStateException(
                "Gemini requires ARCCLAW_GEMINI_API_KEY"));

        return AiGeminiChatLanguageModel.builder()
            .key(key)
            .modelId(modelName)
            .temperature(agentConfig.temperature())
            .maxOutputTokens(agentConfig.maxTokens())
            .timeout(agentConfig.timeout())
            .logRequests(logRequests)
            .logResponses(logResponses)
            .build();
    }
}
