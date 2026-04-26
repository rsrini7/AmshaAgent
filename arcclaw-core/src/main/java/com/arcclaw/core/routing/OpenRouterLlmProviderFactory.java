package com.arcclaw.core.routing;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OpenRouterLlmProviderFactory implements RoutingChatModelProvider {

    @ConfigProperty(name = "arcclaw.openrouter.site-url",
                defaultValue = "https://github.com/rsrini7/arcclaw")
    String siteUrl;

    @ConfigProperty(name = "arcclaw.openrouter.app-name", defaultValue = "ArcClaw")
    String appName;
   
    @Override
    public String providerId() {
        return "openrouter";
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey()
                        .orElseThrow(() -> new IllegalStateException(
                                "OpenRouter requires apiKey - set ARCCLAW_LLM_API_KEY=sk-or-v1-...")))
                .modelName(config.modelName())
                .maxTokens(config.maxTokens().orElse(null))
                .timeout(config.timeout().orElse(null))
                .maxRetries(config.maxRetries().orElse(3))
                .customHeaders(Map.of("HTTP-Referer", siteUrl, "X-Title", appName))
                .temperature(0.0)
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
