package com.arcclaw.core.agent.reasoning;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@link AgentChatModelProvider} for the Groq inference API (SPI 1 — agent reasoning engine).
 * Groq uses the OpenAI API protocol, pointed at api.groq.com.
 */
@ApplicationScoped
public class GroqProviderFactory implements AgentChatModelProvider {

    @ConfigProperty(name = "quarkus.langchain4j.openai.base-url",
                    defaultValue = "https://api.groq.com/openai/v1")
    String baseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    String apiKey;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name",
                    defaultValue = "llama-3.3-70b-versatile")
    String modelName;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.max-tokens",
                    defaultValue = "8192")
    int maxTokens;

    @ConfigProperty(name = "quarkus.langchain4j.openai.timeout",
                    defaultValue = "PT60S")
    Duration timeout;

    @ConfigProperty(name = "arcclaw.llm.log-http", defaultValue = "false")
    boolean logHttp;

    @Override
    public String providerName() {
        return "groq";
    }

    @Override
    public ChatModel create() {
        return OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .maxTokens(maxTokens)
            .timeout(timeout)
            .temperature(0.0)
            .logRequests(logHttp)
            .logResponses(logHttp)
            .build();
    }
}