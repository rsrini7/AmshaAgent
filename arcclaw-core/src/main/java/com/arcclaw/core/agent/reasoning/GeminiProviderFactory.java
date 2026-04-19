package com.arcclaw.core.agent.reasoning;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@link AgentChatModelProvider} for Google Gemini AI using its native SDK.
 */
@ApplicationScoped
public class GeminiProviderFactory implements AgentChatModelProvider {

    @ConfigProperty(name = "arcclaw.llm.gemini.api-key")
    String apiKey;

    @ConfigProperty(name = "arcclaw.llm.gemini.model-name",
                    defaultValue = "gemini-flash-latest")
    String modelName;

    @ConfigProperty(name = "arcclaw.llm.max-output-tokens",
                defaultValue = "8192")
    int maxOutputTokens;

    @ConfigProperty(name = "arcclaw.llm.temperature", defaultValue = "0.1")
    double temperature;

    @ConfigProperty(name = "arcclaw.llm.timeout",
                    defaultValue = "PT60S")
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
        return GoogleAiGeminiChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .timeout(timeout)
            .maxOutputTokens(maxOutputTokens)
            .maxRetries(maxRetries)
            .temperature(temperature)
            .logRequests(logHttp)
            .logResponses(logHttp)
            .build();
    }
}
