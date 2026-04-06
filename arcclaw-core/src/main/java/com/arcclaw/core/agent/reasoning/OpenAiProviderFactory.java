package com.arcclaw.core.agent.reasoning;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OpenAiProviderFactory implements AgentChatModelProvider {

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    String apiKey;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name")
    String modelName;

    @ConfigProperty(name = "arcclaw.llm.log-http", defaultValue = "false")
    boolean logHttp;

    @ConfigProperty(name = "arcclaw.llm.max-retries", defaultValue = "3")
    int maxRetries;

    @ConfigProperty(name = "arcclaw.llm.timeout", defaultValue = "PT60S")
    Duration timeout;

    @Override
    public String providerName() {
        return "openai";
    }

    @Override
    public ChatModel create() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .logRequests(logHttp)
                .logResponses(logHttp)
                .build();
    }
}
