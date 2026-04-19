package com.arcclaw.core.agent.reasoning;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@link AgentChatModelProvider} for Google Gemini AI using its OpenAI-compatible endpoint.
 */
@ApplicationScoped
public class GeminiProviderFactory implements AgentChatModelProvider {


    @ConfigProperty(name = "quarkus.langchain4j.openai.base-url", 
                    defaultValue = "https://generativelanguage.googleapis.com/v1beta/openai/")
    String baseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    String apiKey;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name", defaultValue = "gemini-flash-latest")
    String modelName;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.max-tokens",
                defaultValue = "8192")
    int maxTokens;

    @ConfigProperty(name = "arcclaw.llm.temperature", defaultValue = "0.1")
    double temperature;

    @ConfigProperty(name = "arcclaw.llm.timeout", defaultValue = "PT60S")
    Duration timeout;

    @ConfigProperty(name = "arcclaw.llm.log-http", defaultValue = "false")
    boolean logHttp;

    @ConfigProperty(name = "arcclaw.llm.max-retries", defaultValue = "3")
    int maxRetries;

    @Override
    public String providerName() {
        return "gemini";
    }

    @Override
    public ChatModel create() {

        String effectiveBaseUrl = baseUrl;
        if (effectiveBaseUrl != null && !effectiveBaseUrl.endsWith("/")) {
            effectiveBaseUrl += "/";
        }

        return OpenAiChatModel.builder()
            .baseUrl(effectiveBaseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .timeout(timeout)
            .maxRetries(maxRetries)
            .temperature(temperature)
            .logRequests(logHttp)
            .logResponses(logHttp)
            .build();
    }
}
