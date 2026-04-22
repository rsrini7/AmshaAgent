package com.arcclaw.core.agent.reasoning;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.routing.NvidiaMessageSanitizer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class NvidiaProviderFactory implements AgentChatModelProvider {

    @ConfigProperty(name = "quarkus.langchain4j.openai.base-url", 
                    defaultValue = "https://integrate.api.nvidia.com/v1")
    String baseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    String apiKey;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name",
                    defaultValue = "mistralai/devstral-2-123b-instruct-2512")
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

    @ConfigProperty(name = "arcclaw.llm.structured-output-mode", defaultValue = "NATIVE")
    StructuredOutputMode structuredOutputMode;

    @Override
    public String providerName() {
        return "nvidia";
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return structuredOutputMode;
    }

    @Override
    public ChatModel create() {
        ChatModel base = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .temperature(temperature)
                .logRequests(logHttp)
                .logResponses(logHttp)
                .build();

        return new NvidiaMessageSanitizer(base);
    }
}
