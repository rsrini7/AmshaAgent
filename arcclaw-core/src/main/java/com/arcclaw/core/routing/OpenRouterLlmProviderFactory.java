package com.arcclaw.core.routing;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;
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
        return ChatModelFactory.withOpenRouterHeaders(
                ChatModelFactory.openAiBuilder(
                        config.baseUrl(),
                        config.apiKey().orElseThrow(() -> new IllegalStateException(
                                "OpenRouter requires apiKey - set ARCCLAW_LLM_API_KEY=sk-or-v1-...")),
                        config.modelName(),
                        config.maxTokens().orElse(null),
                        config.timeout().orElse(null),
                        config.maxRetries().orElse(3),
                        0.0, false, false),
                siteUrl, appName)
                .build();
    }
}
