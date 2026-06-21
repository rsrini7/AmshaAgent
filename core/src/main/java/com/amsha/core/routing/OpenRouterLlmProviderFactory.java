package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OpenRouterLlmProviderFactory implements RoutingChatModelProvider {

    @ConfigProperty(name = "amsha.llm.openrouter.site-url", defaultValue = "https://github.com/rsrini7/amsha")
    String siteUrl;

    @ConfigProperty(name = "amsha.llm.openrouter.app-name", defaultValue = "Amsha Agent")
    String appName;

    @Override
    public String providerId() {
        return "openrouter";
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return ChatModelFactory.withOpenRouterHeaders(
                        ChatModelFactory.openAiBuilder(
                                config.baseUrl(),
                                config.apiKey()
                                        .orElseThrow(() -> new IllegalStateException(
                                                "OpenRouter requires apiKey - set AMSHA_LLM_API_KEY=sk-or-v1-...")),
                                config.modelName(),
                                config.maxTokens().orElse(null),
                                config.timeout().orElse(null),
                                config.maxRetries().orElse(3),
                                0.0,
                                false,
                                false),
                        siteUrl,
                        appName)
                .build();
    }
}
