package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.llm.api.LlmConfigKeys;
import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.providers.ChatModelFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class OpenRouterLlmProviderFactory implements RoutingChatModelProvider {

    @ConfigProperty(name = LlmConfigKeys.OPENROUTER_SITE_URL, defaultValue = "https://github.com/rsrini7/amsha")
    String siteUrl;

    @ConfigProperty(name = LlmConfigKeys.OPENROUTER_APP_NAME, defaultValue = "Amsha Agent")
    String appName;

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_OPENROUTER;
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
