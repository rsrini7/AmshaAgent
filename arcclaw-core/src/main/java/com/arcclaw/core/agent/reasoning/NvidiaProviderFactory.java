package com.arcclaw.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.routing.ChatModelFactory;
import com.arcclaw.core.routing.NvidiaMessageSanitizer;
import dev.langchain4j.model.chat.ChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class NvidiaProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "quarkus.langchain4j.openai.base-url", 
                    defaultValue = "https://integrate.api.nvidia.com/v1")
    String baseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    String apiKey;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name",
                    defaultValue = "mistralai/devstral-2-123b-instruct-2512")
    String modelName;

    @Override
    public String providerName() {
        return "nvidia";
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
    }

    @Override
    public ChatModel create() {
        ChatModel base = ChatModelFactory.createOpenAi(
                baseUrl, apiKey, modelName,
                agentConfig.maxTokens(), agentConfig.timeout(), agentConfig.maxRetries(),
                agentConfig.temperature(), agentConfig.logHttp());

        return new NvidiaMessageSanitizer(base);
    }
}
