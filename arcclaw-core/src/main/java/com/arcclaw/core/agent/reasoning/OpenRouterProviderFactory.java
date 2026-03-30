package com.arcclaw.core.agent.reasoning;

import java.time.Duration;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OpenRouterProviderFactory implements AgentChatModelProvider {

   @ConfigProperty(name = "quarkus.langchain4j.openai.base-url",
                    defaultValue = "https://openrouter.ai/api/v1")
    String baseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    String apiKey;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name",
                    defaultValue = "meta-llama/llama-3.1-8b-instruct:free")
    String modelName;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.max-tokens",
                    defaultValue = "8192")
    int maxTokens;

    @ConfigProperty(name = "quarkus.langchain4j.openai.timeout",
                    defaultValue = "PT60S")
    Duration timeout;

    @ConfigProperty(name = "arcclaw.openrouter.site-url",
                    defaultValue = "https://github.com/rsrini7/arcclaw")
    String siteUrl;

    @ConfigProperty(name = "arcclaw.openrouter.app-name", defaultValue = "ArcClaw")
    String appName;

    @Override
    public String providerName() {
        return "openrouter";
    }

    @Override
    public ChatModel create() {
        if ("not-configured".equals(apiKey)) {
            throw new IllegalStateException(
                "OpenRouterProviderFactory selected but arcclaw.openrouter.api-key " +
                "(OPENROUTER_API_KEY) is not set.");
        }
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens) 
                .timeout(timeout)
                // Required by OpenRouter — identifies your app, avoids anonymous rate limits
                .customHeaders(Map.of("HTTP-Referer", siteUrl, "X-Title", appName))
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
