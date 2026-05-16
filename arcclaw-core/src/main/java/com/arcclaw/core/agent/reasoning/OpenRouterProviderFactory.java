package com.arcclaw.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.routing.ChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OpenRouterProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "quarkus.langchain4j.openai.base-url",
                    defaultValue = "https://openrouter.ai/api/v1")
    String baseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    String apiKey;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name",
                    defaultValue = "openai/gpt-oss-20b:free")
    String modelName;

    @ConfigProperty(name = "arcclaw.llm.openrouter.site-url",
                    defaultValue = "https://github.com/rsrini7/arcclaw")
    String siteUrl;

    @ConfigProperty(name = "arcclaw.llm.openrouter.app-name", defaultValue = "ArcClaw")
    String appName;

    @Override
    public String providerName() {
        return "openrouter";
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
    }

    @Override
    public ChatModel create() {
        if ("not-configured".equals(apiKey)) {
            throw new IllegalStateException(
                "OpenRouterProviderFactory selected but arcclaw.openrouter.api-key " +
                "(OPENROUTER_API_KEY) is not set.");
        }
        return ChatModelFactory.withOpenRouterHeaders(
                ChatModelFactory.openAiBuilder(
                        baseUrl, apiKey, modelName,
                        agentConfig.maxTokens(), agentConfig.timeout(), agentConfig.maxRetries(),
                        agentConfig.temperature(), agentConfig.logHttp(), agentConfig.logHttp()),
                siteUrl, appName)
                .build();
    }
}
