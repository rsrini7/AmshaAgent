package com.arcclaw.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.routing.ChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@link AgentChatModelProvider} for the Cerebras AI inference API.
 * Cerebras uses the OpenAI API protocol.
 */
@ApplicationScoped
public class CerebrasProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "quarkus.langchain4j.openai.base-url",
                    defaultValue = "https://api.cerebras.ai/v1")
    String baseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    String apiKey;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name",
                    defaultValue = "qwen-3-235b-a22b-instruct-2507")
    String modelName;

    @Override
    public String providerName() {
        return "cerebras";
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
    }

    @Override
    public ChatModel create() {
        return ChatModelFactory.createOpenAi(
                baseUrl, apiKey, modelName,
                agentConfig.maxTokens(), agentConfig.timeout(), agentConfig.maxRetries(),
                agentConfig.temperature(), agentConfig.logHttp());
    }
}
